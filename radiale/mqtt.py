#!/usr/bin/env python3

import asyncio
from contextlib import AsyncExitStack
from asyncio_mqtt import Client


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

