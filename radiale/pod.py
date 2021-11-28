#!/usr/bin/env python3


import asyncio
import queue
import json
import sys
from bcoding import bencode, bdecode


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
    d = [{"name": "sleep-ms"}]
    for fs in fn_names:
        d.extend(make_clj_code(fs))
    return {"format": "json",
            "ops": {"shutdown": {}},
            "namespaces":
            [{"name": "pod.xlfe.radiale",
              "vars": d}]}


def _write_(d):
    bencode(d, sys.stdout.buffer)
    sys.stdout.buffer.flush()


class RadialePod(object):

    def __init__(self):
        self.running = True

    async def write_raw(self, d):
        await asyncio.get_event_loop().run_in_executor(None, _write_, d)

    async def write_msg(self, id, data, status="status"):
        await self.write_raw(
                dict(value=json.dumps(data), id=id, status=[status]))

    async def run_pod(self):

        while self.running:
            msg = await asyncio.get_event_loop().\
                    run_in_executor(None, bdecode, sys.stdin.buffer)

            op = msg["op"]

            if op == "describe":
                await self.write_raw(describe_this(['listen', 'mdns-info', 'subscribe-esp']))

            elif op == 'shutdown':
                exit(0)

            elif op == "invoke":
                var = msg["var"]
                id = msg["id"]
                opts = json.loads(msg["args"])[0]

                if var.endswith('sleep-ms'):
                    await asyncio.sleep(int(opts)/1000.0)
                    await self.write_msg(id=id, status="done", data=opts)

                elif var.endswith('mdns-info*'):
                    pass
                    # await self.kernel.run(mdns_service_info(id, self.aiozc.zeroconf, opts), daemon=True)

                elif var.endswith('listen*'):
                    pass
                    # await self.kernel.run(self.listen(id, opts))

                elif var.endswith('subscribe-esp*'):
                    pass
                    # await curio.spawn(subscribe_esp, id, opts, daemon=True)

    async def listen(self, id, opts):

        if opts['service'] == 'mdns':
            await self.mdns_listen(id, opts)

        elif opts['service'] == 'deconz':
            await self.deconz(id, opts)

        elif opts['service'] == 'mqtt':
            asyncio.ensure_future(self.mqtt(id, opts))

        else:
            raise Exception("Unknown service: {}".format(opts['service']))

    def shutdown(self):
        self.running = False


if __name__ == "__main__":

    loop = asyncio.get_event_loop()
    pod = RadialePod(loop)
    asyncio.run(pod.run_pod())



