#!/usr/bin/env python3

from zeroconf import IPVersion, ServiceStateChange
from zeroconf.asyncio import AsyncServiceBrowser, \
        AsyncServiceInfo, AsyncZeroconf

import curio
from typing import cast
import json
import asyncio


state_map = {
        ServiceStateChange.Added: "added",
        ServiceStateChange.Removed: "removed",
        ServiceStateChange.Updated: "updated"}


async def mdns_state_change(id, zeroconf, service_type, name, state_change):
    return dict(
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

    return dict(id=id, data=data)


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


