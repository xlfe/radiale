import dmcast
import asyncio
from . import pod

SERVICE_TYPE = "_googlecast._tcp.local."


class Chromecast():

    def init(self, out, id, mdns, sn):

        self.out = out
        self.id = id
        self.mdns = mdns
        self.sn = sn

    async def connect(self):

        pod.eprint(f'Chromecast connecting {self.service_name}')
        info = await self.mdns.get_info(
                SERVICE_TYPE, f'{self.service_name}.{SERVICE_TYPE}')

        assert info is not None
        hosts = info.parsed_scoped_addresses()
        if not hosts:
            raise Exception(f'Not found: {self.service_name}')

        self.cc = dmcast.Chromecast(hosts[0], info.port)

        async def notify_state(cc, state):
            self.out.write_msg(id=self.id, data={
                "service-name": self.service_name,
                "state": state
            })

        self.cc.notify_state = notify_state
        await self.cc.start()

