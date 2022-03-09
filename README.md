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

radiale is designed to be run using the babashka pod framework

see `example.bb`

```bash
bb example.bb
```

The output would be something like :-


```
{:service-name "Google-Home-Mini-<BLAH>._googlecast._tcp.local.", :service-type "_googlecast._tcp.local.", :state-change "added", :opts {:service-type "_googlecast._tcp.local."}, :fn-name :listen-mdns}
{:service-name "Google-Home-Mini-<BLAH>._googlecast._tcp.local.", :service-type "_googlecast._tcp.local.", :state-change "added", :opts {:service-type "_googlecast._tcp.local."}, :fn-name :listen-mdns}
{:service-name "myesphome._esphomelib._tcp.local.", :service-type "_esphomelib._tcp.local.", :state-change "added", :opts {:service-type "_esphomelib._tcp.local."}, :fn-name :listen-mdns}
{:topic "$SYS/broker/version", :payload "HBMQTT version 0.8.5", :opts {:host "<HOST>.lan", :username "homeassistant"}, :fn-name :listen-mqtt}
{:topic "home/system/availability", :payload "online", :opts {:host "<HOST>.lan", :username "homeassistant"}, :fn-name :listen-mqtt}
{:attr {:lastannounced "2021-11-24T09:14:14Z", :name "FancyLight", :type "Dimmable light", :swversion "1.0.0", :modelid "Hue 1000lm", :manufacturername "Phillips", :id "A", :uniqueid "<BLAH>", :lastseen "2021-11-28T10:27Z"}, :e "changed", :id "A", :r "lights", :t "event", :uniqueid "<BLAH>", :opts {:host "<HOST>.lan"}, :fn-name :listen-deconz}
"myesphome._esphomelib._tcp.local." {:object_id "myesp_wifi", :state -65.25}
{:topic "home/system/availability", :payload "offline", :opts {:host "<HOST>.lan", :username "homeassistant"}, :fn-name :listen-mqtt}
"myesphome._esphomelib._tcp.local." {:object_id "myesp_wifi", :state -66.35}
...
```
