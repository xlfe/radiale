from zeroconf import IPVersion, ServiceStateChange
from zeroconf.asyncio import AsyncServiceBrowser, \
        AsyncServiceInfo, AsyncZeroconf

from typing import cast
import asyncio
from . import pod


state_map = {
        ServiceStateChange.Added: "added",
        ServiceStateChange.Removed: "removed",
        ServiceStateChange.Updated: "updated"}


async def mdns_state_change(o, id, zeroconf, service_type, name, state_change):
    name, _ = name.split('.', 1)
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

    async def get_info(self, st, sn):
        info = AsyncServiceInfo(st, f'{sn}.{st}')
        await info.async_request(self.aiozc.zeroconf, 3000)
        return info

    async def info(self, id, opts):

        info = await self.get_info(opts['service-type'], opts['service-name'])

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
                    # Handle cases where key or value might be None or already str
                    key_str = key.decode('utf-8') if isinstance(key, bytes) else str(key) if key else ''
                    value_str = value.decode('utf-8') if isinstance(value, bytes) else str(value) if value else ''
                    data['properties'][key_str] = value_str

        self.out.write_msg(id=id, data=data)

    async def listen(self, id, opts):
        service = opts['service-type']

        if service not in self.browsers:

            def wrapper(**kwds):
                asyncio.ensure_future(mdns_state_change(self.out, id, **kwds))

            self.browsers[service] = AsyncServiceBrowser(
                self.aiozc.zeroconf, service, handlers=[wrapper]
            )


