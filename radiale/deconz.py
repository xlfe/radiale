import json
import websockets
import aiohttp


def make_host(opts):
    return f"http://{opts['host']}:{opts['port'] if 'port' in opts else 80}/api/{opts['api-key']}"


class Deconz:
    def __init__(self):
        self.uri = self.ws = None

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
                out.write_msg(id=id, data=config_data)

                self.uri = "ws://{}:{}".format(
                        opts['host'],
                        config_data['config']['websocketport'])

        if self.uri:
            self.ws = await websockets.connect(self.uri)

            try:
                while True:
                    if not self.ws.open:
                        self.ws = await websockets.connect(self.uri)
                    else:
                        data = await self.ws.recv()
                        out.write_msg(id=id, data=json.loads(data))

            except websockets.exceptions.ConnectionClosedOK:
                pass
