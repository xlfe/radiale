from asyncio_mqtt import Client


async def mqtt_listen(out, id, opts):

    client = Client(opts['host'])

    if 'username' in opts or 'password' in opts:
        client._client.username_pw_set(
                opts['username'] if 'username' in opts else None,
                opts['password'] if 'password' in opts else None)

    async with client:
        async with client.unfiltered_messages() as messages:
            await client.subscribe("#")
            async for message in messages:
                out.write_msg(
                        id=id,
                        data={
                            "topic": message.topic,
                            "payload": message.payload.decode()})
