import json
import websockets
import aiohttp


class Deconz:
    def __init__(self):
        self.uri = self.ws = None

    async def listen(self, out, id, opts):
        config = "http://{}:{}/api/{}".format(
                opts['host'], opts['port'] if 'port' in opts else 80,
                opts['api-key']
                )

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
                    data = await self.ws.recv()
                    out.write_msg(id=id, data=json.loads(data))
            except websockets.exceptions.ConnectionClosedOK:
                pass
