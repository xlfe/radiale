import json
import asyncio
import websockets
from websockets.exceptions import ConnectionClosed
import aiohttp

from .logging import eprint, LOG_WARNING


def make_host(opts):
    return f"http://{opts['host']}:{opts['port'] if 'port' in opts else 80}/api/{opts['api-key']}"


class Deconz:
    def __init__(self):
        self.uri = None

    async def put(self, out, id, opts, type_name, device_id, state):

        ep = f'{make_host(opts)}/{type_name}/{device_id}/state'

        async with aiohttp.ClientSession() as session:
            async with session.put(ep, json=state) as response:
                response_data = await response.json()
                out.write_msg(id=id, data=dict(success=response.ok, data=response_data))

    async def listen(self, out, id, opts):
        config = make_host(opts)

        async with aiohttp.ClientSession() as session:
            async with session.get(config) as response:
                config_data = await response.json()
                out.write_msg(id=id, data=dict(radialeconfig=config_data))

                self.uri = "ws://{}:{}".format(
                        opts['host'],
                        config_data['config']['websocketport'])

        if self.uri:
            # Use websockets' built-in reconnection pattern (new in v10+)
            # This handles transient disconnects with exponential backoff
            async for ws in websockets.connect(self.uri):
                try:
                    eprint(f"Deconz: Connected to {self.uri}")
                    async for message in ws:
                        out.write_msg(id=id, data=json.loads(message))
                except ConnectionClosed:
                    eprint("Deconz: Connection closed, reconnecting...", level=LOG_WARNING)
                    continue
