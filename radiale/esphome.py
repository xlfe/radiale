import asyncio
import aioesphomeapi
from aioesphomeapi.core import APIConnectionError
from . import pod
from typing import cast


SERVICE_TYPE = "_esphomelib._tcp.local."


class ESPHome(object):

    def __init__(self, out, id, mdns, service_name):

        assert '.' not in service_name
        self.mdns = mdns
        self.service_name = service_name
        self.cli = None
        self.id = id
        self.out = out
        self.retries = 0
        self.connecting = False
        self.connecting_lock = asyncio.Lock()

    async def connected_state(self, connected):
        self.out.write_msg(id=self.id, data={
            "service-name": self.service_name,
            "connected": connected
        })

    async def on_disconnect(self):
        pod.eprint(f'ESP Disconnected from {self.service_name}')
        await self.connected_state(False)

        while self.retries < 15:

            async with self.connecting_lock:
                self.connecting = True

            await asyncio.sleep(5)

            pod.eprint(f'ESP try {self.retries} reconnect {self.service_name}')
            try:
                await self.connect()
                await self.connected_state(True)
                await asyncio.create_task(self.subscribe())
                self.retries = 0
                pod.eprint(f'ESP: Reconnected to {self.service_name}')
                break
            except APIConnectionError:
                self.retries += 1
                continue

        async with self.connecting_lock:
            self.connecting = False

    async def connect(self):

        pod.eprint(f'ESP Connecting {self.service_name}')
        info = await self.mdns.get_info(
                SERVICE_TYPE, f'{self.service_name}.{SERVICE_TYPE}')

        assert info is not None
        hosts = info.parsed_scoped_addresses()
        if not hosts:
            raise APIConnectionError()

        self.cli = aioesphomeapi.APIClient(hosts[0], info.port, None)
        await self.cli.connect(on_stop=self.on_disconnect, login=True)

    async def subscribe(self):

        def esp_change_callback(state):
            self.out.write_msg(
                    id=self.id,
                    data={
                        "service-name": self.service_name,
                        "state": [str(state.key), state.state]
                        })

        await self.cli.subscribe_states(esp_change_callback)

    async def update_services(self):

        sensors = await self.cli.list_entities_services()
        service_details = {}

        for s in sensors[0]:
            service_details[str(s.key)] = \
                {**s.to_dict(), **{"type": "service"}}

        for s in sensors[1]:
            service_details[str(s.key)] = \
                {**s.to_dict(), **{"type": "user-defined-service"}}

        self.out.write_msg(
                id=self.id,
                data={
                    "service-name": self.service_name,
                    "services": service_details
                    }
                )

    async def switch_command(self, id, key, state):
        await self.cli.switch_command(key, state)
        self.out.write_msg(id=id, data={"success": True})

    async def light_command(self, id, key, params):
        await self.cli.light_command(key, **params)
        self.out.write_msg(id=id, data={"success": True})


