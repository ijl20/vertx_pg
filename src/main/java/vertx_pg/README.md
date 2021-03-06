# [Platform](https://github.com/ijl20/tfc_server) &gt; MsgRouter

MsgRouter is part of the Adaptive City Platform
supported by the Smart Cambridge programme.

## Overview

MsgRouter subscribes to an eventbus address, filters the messages received, and forwards messages to
defined destination addresses.

## Sample MsgRouter service config file

```
                                                                                
{
    "main":    "uk.ac.cam.tfc_server.msgrouter.MsgRouter",
    "options":
        { "config":
          {

            "module.name":           "msgrouter",
            "module.id":             "everynet_feed_test",

            "eb.system_status":      "tfc.system_status",
            "eb.console_out":        "tfc.console_out",
            "eb.manager":            "tfc.manager",

            "msgrouter.log_level":     1,

            "msgrouter.address": "tfc.msgrouter.everynet_feed_test",

            "msgrouter.routers":
            [
                { 
                    "source_address": "tfc.everynet_feed.test",
                    "source_type":    "general",
                    "source_field":   "request_data",
                    "source_index":   0,
                    "source_filter": { 
                                         "field": "dev_eui",
                                         "compare": "=",
                                         "value": "0018b2000000113e"
                                     },
                    "destination_id": "test_0018b2000000113e",
                    "http_token": "test-msgrouter-post",
                    "url":         "http://localhost:8098/everynet_feed/test/adeunis_test2"
                },
                { 
                    "source_address": "tfc.everynet_feed.A",
                    "source_type": "everynet_jsonrpc"
                }
            ]
              
          }
        }
}
```

## API via 'tfc.manager' eventbus messages

Note in general these messages are generated by HttpMsg, which adds the "module_name", "module_id",
"to_module_name", "to_module_id" fields.  The actual POST to HttpMsg will contain the "method" and
"params" fields.

### Sample add_destination JSON message

```
{ "module_name":"httpmsg",
  "module_id":"test"
  "to_module_name":"msgrouter",
  "to_module_id":"test",
  "method":"add_destination",
  "params":{ "info": { "destination_id": "xyz",
                       "http_token":"foo!bar",
                       "url": "http://localhost:8080/efgh"
                     }
           }
}
```

### remove_destination

```
{ "module_name":"httpmsg",
  "module_id":"test"
  "to_module_name":"msgrouter",
  "to_module_id":"test",
  "method":"remove_destination",
  "params":{ "info": { "destination_id": "xyz"
                     }
           }
}
```

### Sample add_sensor JSON message

```
{ "module_name":"httpmsg",
  "module_id":"test"
  "to_module_name":"msgrouter",
  "to_module_id":"test",
  "method":"add_sensor",
  "params":{ "info": { "sensor_id": "abc",
                       "sensor_type": "lorawan",
                       "destination_id": "xyz"
                     }
           }
}
```

### Remove sensor

```
{ "module_name":"httpmsg",
  "module_id":"test"
  "to_module_name":"msgrouter",
  "to_module_id":"test",
  "method":"remove_sensor",
  "params":{ "info": { "sensor_id": "abc",
                     }
           }
}
```

