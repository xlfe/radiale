# radiale

what if home-automation was also homoiconic?

> The upper or proximal row contains three bones, to which Gegenbaur has applied the terms radiale, intermedium, and ulnare, the first being on the radial or preaxial side of the limb.


### status

This is a work in progress. 

Working currently :-

* MQTT Client
* MDNS listener + get info
* ESP32 state subscription
* ESP32 switch command
* deconz event listener (websocket)
* deconz actions
* simple scheduling

Todo
* chromecast
* MQTT Broker

### running radiale

radiale is designed to work as a babashka script

see `example.bb`

```bash
bb example.bb
```
