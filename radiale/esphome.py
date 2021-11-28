import aioesphomeapi


async def subscribe_esp(out, id, opts):
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

    out.write_msg(
            id=id,
            data={
                "service-name": opts['service-name'],
                "services": service_details
                }
            )

    def esp_change_callback(state):
        out.write_msg(
                id=id,
                data={
                    "service-name": opts['service-name'],
                    "state": [str(state.key), state.state]
                    })

    await cli.subscribe_states(esp_change_callback)
