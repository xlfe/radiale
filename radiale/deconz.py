import json
import websockets
import aiohttp
import asyncio


class Deconz:
    def __init__(self):
        pass

    async def deconz(self, id, opts):
        config = "http://{}:{}/api/{}".format(
                opts['host'], opts['port'] if 'port' in opts else 80,
                opts['api-key']
                )

        ws = None
        async with aiohttp.ClientSession() as session:
            async with session.get(config) as response:

                d = await response.json()
                write_msg(id=id, data=d)

                ws = "ws://{}:{}".format(opts['host'], d['config']['websocketport'])

        if ws:
            self.deconzws = await websockets.connect(ws)

            try:
                while self.running:
                    data = await self.deconzws.recv()
                    write_msg(id=id, data=json.loads(data))
                    await asyncio.sleep(0.1)
            except websockets.exceptions.ConnectionClosedOK:
                pass

