from . import mdns, esphome, deconz, mqtt
import asyncio
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


class OutgoingQ():

    async def start(self):
        self.running = True
        self.outgoing = asyncio.Queue()
        asyncio.create_task(self.out_task(), name="Output writer")
        return self

    async def out_task(self):
        while self.running:
            d = await self.outgoing.get()
            await asyncio.get_event_loop().run_in_executor(None, _write_, d)

    def write_raw(self, d):
        self.outgoing.put_nowait(d)

    def write_msg(self, id, data, status="status"):
        self.write_raw(
                dict(value=json.dumps(data), id=id, status=[status]))


class RadialePod(object):

    def __init__(self):
        self.running = True
        self.services = {}

    async def run_pod(self):
        self.out = await OutgoingQ().start()

        while self.running:
            msg = await asyncio.get_event_loop().\
                    run_in_executor(None, bdecode, sys.stdin.buffer)

            op = msg["op"]

            if op == "describe":
                self.out.write_raw(
                        describe_this([
                            'listen-mdns',
                            'listen-mqtt',
                            'listen-deconz',
                            'mdns-info',
                            'subscribe-esp'])
                        )

            elif op == 'shutdown':
                self.running = self.out.running = False

            elif op == "invoke":
                var = msg["var"]
                id = msg["id"]
                opts = json.loads(msg["args"])[0]

                if var.endswith('sleep-ms'):
                    await asyncio.sleep(int(opts)/1000.0)
                    self.out.write_msg(id=id, status="done", data=opts)

                elif var.endswith('listen-mdns*'):
                    if 'mdns' not in self.services:
                        self.services['mdns'] = \
                                await mdns.MDNS().start(self.out)
                    asyncio.create_task(self.services['mdns'].listen(id, opts))

                elif var.endswith('mdns-info*'):
                    assert 'mdns' in self.services
                    asyncio.create_task(self.services['mdns'].info(id, opts))

                elif var.endswith('listen-deconz*'):
                    assert 'deconz' not in self.services
                    self.services['deconz'] = deconz.Deconz()
                    asyncio.create_task(
                        self.services['deconz'].listen(self.out, id, opts))

                elif var.endswith('listen-mqtt*'):
                    asyncio.create_task(
                            mqtt.mqtt_listen(self.out, id, opts))

                elif var.endswith('subscribe-esp*'):
                    asyncio.create_task(
                        esphome.subscribe_esp(self.out, id, opts))
