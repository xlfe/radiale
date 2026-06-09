import asyncio
import inspect
import aioesphomeapi
from aioesphomeapi.core import APIConnectionError
from aioesphomeapi.model import UserService
from typing import cast

from .logging import eprint, LOG_ERR, LOG_WARNING, LOG_INFO


SERVICE_TYPE = "_esphomelib._tcp.local."
CONNECT_TIMEOUT = 30   # hard cap; aioesphomeapi's own timeout doesn't fire on a stalled post-TCP handshake


class ESPHome():

    def __init__(self, out, id, mdns, service_name):

        assert '.' not in service_name
        self.mdns = mdns
        self.service_name = service_name
        self.cli = None
        self.id = id
        self.out = out
        self.retries = 0
        self.connected = False
        self.connecting = False
        self.connecting_lock = asyncio.Lock()

    async def connected_state(self, connected):
        self.connected = connected
        self.out.write_msg(id=self.id, data={
            "service-name": self.service_name,
            "connected": connected
        })

    async def _close_cli(self):
        # null first so reentrant on_stop/on_disconnect, the mDNS path, and command
        # handlers all see "not connected" before we await the disconnect.
        self.connected = False
        cli, self.cli = self.cli, None
        if cli is not None:
            try:
                await cli.disconnect(force=True)
            except Exception:
                pass

    def _is_live(self):
        cli = self.cli
        return (
            self.connected
            and cli is not None
            and getattr(cli, "_connection", None) is not None
        )

    async def on_disconnect(self, expected: bool = False):
        eprint(f'ESP Disconnected from {self.service_name} (expected={expected})',
               level=LOG_WARNING if not expected else LOG_INFO)

        # Claim ownership BEFORE any await so the mDNS path (subscribe-esp*) can't
        # double-connect during the wait below — that race is what hung us. Also
        # guards the reentrant on_stop fired by _close_cli().
        async with self.connecting_lock:
            if self.connecting:
                return
            self.connecting = True

        try:
            await self._close_cli()          # dead client -> commands now fail fast (success:false)
            await self.connected_state(False)

            # For expected disconnects (e.g. OTA reboot), wait longer before reconnecting
            if expected:
                await asyncio.sleep(30)

            backoff = 5
            while True:                       # never abandon the device; back off and keep trying
                eprint(f'ESP try {self.retries} reconnect {self.service_name}')
                try:
                    await self.connect()
                    await self.update_services()
                    await self.connected_state(True)
                    await self.subscribe()
                    self.retries = 0
                    eprint(f'ESP: Reconnected to {self.service_name}')
                    return
                except (APIConnectionError, asyncio.TimeoutError):
                    self.retries += 1
                    await asyncio.sleep(backoff)
                    backoff = min(backoff * 2, 60)
        finally:
            async with self.connecting_lock:
                self.connecting = False

    async def connect(self):

        eprint(f'ESP Connecting {self.service_name}')
        info = await self.mdns.get_info(SERVICE_TYPE, self.service_name)

        if info is None:
            eprint(f'ESP: No mDNS info found for {self.service_name}', level=LOG_ERR)
            raise APIConnectionError(f"No mDNS info for {self.service_name}")

        hosts = info.parsed_scoped_addresses()
        if not hosts:
            eprint(f'ESP: No hosts found for {self.service_name}', level=LOG_ERR)
            raise APIConnectionError(f"No hosts for {self.service_name}")

        await self._close_cli()              # never stack a 2nd connection — this is what hung
        self.cli = aioesphomeapi.APIClient(hosts[0], info.port, None)
        try:
            await asyncio.wait_for(
                self.cli.connect(on_stop=self.on_disconnect, login=True),
                timeout=CONNECT_TIMEOUT,
            )
        except (aioesphomeapi.core.TimeoutAPIError, asyncio.TimeoutError):
            await self._close_cli()
            raise APIConnectionError(f"Timeout connecting to {self.service_name}")
        except Exception:
            await self._close_cli()
            raise

    async def subscribe(self):
        if self.cli is None:
            eprint(f'ESP: Cannot subscribe - not connected to {self.service_name}', level=LOG_ERR)
            return

        def esp_change_callback(state):
            self.out.write_msg(
                    id=self.id,
                    data={
                        "service-name": self.service_name,
                        "state": [str(state.key), state.state]
                        })

        def esp_subscribe_ha_state(entity_id, attribute):
            self.out.write_msg(
                    id=self.id,
                    data={
                        "service-name": self.service_name,
                        "ha-state-subscribe": [entity_id, attribute]
                        })

        # subscribe_states and subscribe_home_assistant_states are synchronous in newer aioesphomeapi
        self.cli.subscribe_states(esp_change_callback)
        self.cli.subscribe_home_assistant_states(esp_subscribe_ha_state)

    async def update_services(self):
        if self.cli is None:
            eprint(f'ESP: Cannot update services - not connected to {self.service_name}', level=LOG_ERR)
            return

        sensors = await self.cli.list_entities_services()
        service_details = {}

        for s in sensors[0]:
            service_details[str(s.key)] = \
                {**s.to_dict(), **{"type": "service"}}

        for s in sensors[1]:
            service_details[str(s.key)] = \
                {**s.to_dict(), **{"type": "user-defined-service"}}

        self.service_details = service_details
        self.out.write_msg(
                id=self.id,
                data={
                    "service-name": self.service_name,
                    "services": service_details
                    }
                )

    async def _maybe_await(self, result):
        # aioesphomeapi flips these fire-and-forget commands between sync and
        # async across versions (e.g. execute_service is sync in 42.x but a
        # coroutine in 45.x). Await the result iff it's awaitable, so the command
        # is actually sent regardless of the installed version — calling an async
        # method without await silently drops it (success:true, nothing sent).
        if inspect.isawaitable(result):
            await result

    async def switch_command(self, id, key, state):
        if not self._is_live():
            self.out.write_msg(id=id, data={"success": False, "error": "Not connected"})
            return
        await self._maybe_await(self.cli.switch_command(key, state))
        self.out.write_msg(id=id, data={"success": True})

    async def light_command(self, id, key, params):
        if not self._is_live():
            self.out.write_msg(id=id, data={"success": False, "error": "Not connected"})
            return
        await self._maybe_await(self.cli.light_command(key, **params))
        self.out.write_msg(id=id, data={"success": True})

    async def service_command(self, id, key, params):
        if not self._is_live():
            self.out.write_msg(id=id, data={"success": False, "error": "Not connected"})
            return
        svc = self.service_details[str(key)].copy()
        svc.pop('type')
        service = UserService(**svc)
        # execute_service is one-way (no device ACK): success:true means only that
        # we sent it on a live connection, NOT that the device acted.
        await self._maybe_await(self.cli.execute_service(service, params))
        self.out.write_msg(id=id, data={"success": True})

    async def state_update(self, id, entity_id, attribute, state):
        if not self._is_live():
            self.out.write_msg(id=id, data={"success": False, "error": "Not connected"})
            return
        await self._maybe_await(self.cli.send_home_assistant_state(entity_id, attribute, state))
        self.out.write_msg(id=id, data={"success": True})


