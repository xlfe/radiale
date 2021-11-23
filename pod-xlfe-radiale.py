#!/usr/bin/env python3

from zeroconf import IPVersion, ServiceStateChange
from zeroconf.asyncio import AsyncServiceBrowser, \
        AsyncServiceInfo, AsyncZeroconf

from typing import cast
import signal
import json
import pychromecast
import websockets
import aiohttp
import aioesphomeapi
import asyncio
import sys
from bcoding import bencode, bdecode
from contextlib import AsyncExitStack
from asyncio_mqtt import Client


async def read():
    return dict(bdecode(sys.stdin.buffer))


def write_raw(d):
    bencode(d, sys.stdout.buffer)
    sys.stdout.buffer.flush()


def write_msg(id, data, status="status"):
    bencode(
        dict(
            value=json.dumps(data),
            id=id,
            status=[status]),
        sys.stdout.buffer)
    sys.stdout.buffer.flush()


def eprint(e):
    sys.stderr.buffer.write(e.encode('utf-8'))
    sys.stderr.buffer.flush()


def make_clj_code(fn_name):
    return [{"name": '{}*'.format(fn_name)},
            {"name": fn_name, "code": """
(defn """ + fn_name + """
  ([opts cb _])
  ([opts cb]
   (babashka.pods/invoke
    "pod.xlfe.radiale"
    'pod.xlfe.radiale/""" + fn_name + """*
    [opts]
    {:handlers
     {:success (fn [event] (cb (assoc event
                                 :opts (dissoc opts :password :api-key)
                                 :fn-name :""" + fn_name + """)))
      :error (fn [{:keys [:ex-message :ex-data]}]
               (binding [*out* *err*]
                 (println "ERROR:" ex-message)))}})
   nil))
"""}]


def describe_this(fn_names):
    d = [{"name": "ping"}]
    for fs in fn_names:
        d.extend(make_clj_code(fs))
    return {"format": "json",
            "ops": {"shutdown": {}},
            "namespaces":
            [{"name": "pod.xlfe.radiale",
              "vars": d}]}


async def subscribe_esp(id, opts):
    host, port = opts['addresses'][0]
    cli = aioesphomeapi.APIClient(host, port, None)

    await cli.connect(login=True)
    sensors = await cli.list_entities_services()
    service_details = {}

    for s in sensors[0]:
        service_details[str(s.key)] = \
            {**s.to_dict(), **{"type": "service"}}

    for s in sensors[1]:
        service_details[str(s.key)] = \
            {**s.to_dict(), **{"type": "user-defined-service"}}

    write_msg(
            id=id,
            data={
                "service-name": opts['service-name'],
                "services": service_details
                }
            )

    def esp_change_callback(state):
        write_msg(
                id=id,
                data={
                    "service-name": opts['service-name'],
                    "state": [str(state.key), state.state]
                    })

    await cli.subscribe_states(esp_change_callback)


state_map = {
        ServiceStateChange.Added: "added",
        ServiceStateChange.Removed: "removed",
        ServiceStateChange.Updated: "updated"}


async def mdns_state_change(id, zeroconf, service_type, name, state_change):
    write_msg(
            id=id,
            data={
                "service-name": name,
                "service-type": service_type,
                "state-change": state_map[state_change]})


async def mdns_service_info(id, zeroconf, opts):
    info = AsyncServiceInfo(opts['service-type'], opts['service-name'])
    await info.async_request(zeroconf, 3000)

    data = None
    if info:
        data = {**opts, **{
                'addresses': [
                    [add, cast(int, info.port)]
                    for add in info.parsed_scoped_addresses()],
                'weight': info.weight,
                'priority': info.priority,
                'server': info.server}}
        if info.properties:
            data['properties'] = {}
            for key, value in info.properties.items():
                data['properties'][key.decode('utf-8')] = value.decode('utf-8')

    write_msg(id=id, data=data)


async def cancel_tasks(tasks):
    for task in tasks:
        if task.done():
            continue
        task.cancel()
        try:
            await task
        except asyncio.CancelledError:
            pass


async def mqtt_messages(id, messages):
    async for message in messages:
        write_msg(
                id=id,
                data={
                    "topic": message.topic,
                    "payload": message.payload.decode()})


class AsyncRunner:
    def __init__(self):
        self.running = True
        self.browsers = {}
        self.deconzws = None
        self.aiozc = None

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

    async def mdns_listen(self, id, opts):
        service = opts['service-type']
        if self.aiozc is None:
            self.aiozc = AsyncZeroconf(ip_version=IPVersion.V4Only)

        if service not in self.browsers:

            def wrapper(**kwds):
                asyncio.ensure_future(mdns_state_change(id, **kwds))

            self.browsers[service] = AsyncServiceBrowser(
                self.aiozc.zeroconf, service, handlers=[wrapper]
            )

    async def async_run(self):

        while self.running:
            await asyncio.sleep(0.1)
            msg = await read()

            op = msg["op"]

            if op == "describe":
                write_raw(describe_this(
                    ['listen', 'mdns-info', 'subscribe-esp']
                    ))

            elif op == 'shutdown':
                exit(1)
                # await self.async_close()

            elif op == "invoke":
                var = msg["var"]
                id = msg["id"]
                opts = json.loads(msg["args"])[0]

                if var.endswith('ping'):
                    write_msg(id=id, status="done", data=opts)

                elif var.endswith('mdns-info*'):
                    await mdns_service_info(id, self.aiozc.zeroconf, opts)

                elif var.endswith('listen*'):
                    await self.listen(id=id, opts=opts)

                elif var.endswith('subscribe-esp*'):
                    await subscribe_esp(id=id, opts=opts)

    async def listen(self, id, opts):

        if opts['service'] == 'mdns':
            await self.mdns_listen(id, opts)

        elif opts['service'] == 'deconz':
            await self.deconz(id, opts)

        elif opts['service'] == 'mqtt':
            asyncio.ensure_future(self.mqtt(id, opts))

        else:
            raise Exception("Unknown service: {}".format(opts['service']))

    async def mqtt(self, id, opts):

        async with AsyncExitStack() as stack:
            tasks = set()
            stack.push_async_callback(cancel_tasks, tasks)

            client = Client(opts['host'])

            if 'username' in opts or 'password' in opts:
                client._client.username_pw_set(
                        opts['username'] if 'username' in opts else None,
                        opts['password'] if 'password' in opts else None)

            await stack.enter_async_context(client)

            messages = await stack.enter_async_context(client.unfiltered_messages())
            task = asyncio.create_task(mqtt_messages(id, messages))
            tasks.add(task)

            await client.subscribe("#")
            await asyncio.gather(*tasks)

    async def async_close(self):
        self.running = False
        if self.aiozc:
            await self.aiozc.async_close()
            self.aiozc = None
        if self.deconzws:
            await self.deconzws.close()
            self.deconzws = None
        for k, v in self.browsers.items():
            await v.async_cancel()
        self.browsers = {}

        # for task in asyncio.all_tasks():
            # task.cancel()


async def shutdown(signal, runner, loop):
    runner.running = False
    tasks = [t for t in asyncio.all_tasks() if t is not
             asyncio.current_task()]

    [task.cancel() for task in tasks]

    await asyncio.gather(*tasks, return_exceptions=True)
    loop.stop()


if __name__ == "__main__":

    loop = asyncio.get_event_loop()
    runner = AsyncRunner()

    signals = (signal.SIGHUP, signal.SIGTERM, signal.SIGINT)
    for s in signals:
        loop.add_signal_handler(
            s, lambda s=s: asyncio.create_task(shutdown(s, runner, loop)))
    try:
        loop.run_until_complete(runner.async_run())
    except KeyboardInterrupt:
        asyncio.create_task(shutdown(None, runner, loop))
        # pass
    except Exception as e:
        eprint("Exception" + str(e))
        raise
    finally:
        # loop.run_until_complete(runner.async_close())
        asyncio.create_task(shutdown(None, runner, loop))
        loop.stop()
        loop.close()

