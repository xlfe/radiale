from zeroconf import IPVersion, ServiceStateChange
from zeroconf.asyncio import AsyncServiceBrowser, \
        AsyncServiceInfo, AsyncZeroconf

from typing import cast
import asyncio


state_map = {
        ServiceStateChange.Added: "added",
        ServiceStateChange.Removed: "removed",
        ServiceStateChange.Updated: "updated"}


async def mdns_state_change(o, id, zeroconf, service_type, name, state_change):
    o.write_msg(
                id=id,
                data={
                    "service-name": name,
                    "service-type": service_type,
                    "state-change": state_map[state_change]})


class MDNS():
    async def start(self, out):
        self.out = out
        self.aiozc = AsyncZeroconf(ip_version=IPVersion.V4Only)
        self.browsers = {}
        return self

    async def info(self, id, opts):
        info = AsyncServiceInfo(opts['service-type'], opts['service-name'])
        await info.async_request(self.aiozc.zeroconf, 3000)

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
                    data['properties'][key.decode('utf-8')] =\
                        value.decode('utf-8')

        self.out.write_msg(id=id, data=data)

    async def listen(self, id, opts):
        service = opts['service-type']

        if service not in self.browsers:

            def wrapper(**kwds):
                asyncio.ensure_future(mdns_state_change(self.out, id, **kwds))

            self.browsers[service] = AsyncServiceBrowser(
                self.aiozc.zeroconf, service, handlers=[wrapper]
            )


