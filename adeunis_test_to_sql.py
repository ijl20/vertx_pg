#!/usr/bin/env python3

# author: Ian Lewis ijl20
# date: 2017-07-25
#
# Reads a list of 'Adeunis test device' LoraWAN sensor readings provided by the EveryNet gateways
# from standard input
# and outputs SQL for importing into PostgreSQL / PostGIS
# E.g. (each json record on a single line):
# { "module_name":"everynet_feed",
#   "module_id":"A",
#   "msg_type":"everynet_ascii_hex",
#   "feed_id":"ascii_hex",
#   "filename":"1497506024_2017-06-15-06-53-44",
#   "filepath":"2017/06/15",
#   "ts":1497506024,
#   "dev_eui":"0018b2000000113e",
#   "decoded_payload":"9E15521268800000553079390F76",
#   "request_data":[{
#       "params":{
#           "payload":"nhVSEmiAAABVMHk5D3Y=","port":1,"dev_addr":"161ebb56",
#           "radio":{"stat":1,"gw_band":"EU863-870","server_time":1.497506024182017E9,"modu":"LORA","gw_gps":{"lat":52.20506,"alt":68,"lon":0.10844},"gw_addr":"70b3d54b13c80000","chan":6,"gateway_time":1.497506024141793E9,"datr":"SF12BW125","tmst":3024153924,"codr":"4/5","rfch":1,"lsnr":-11.2,"rssi":-102,"freq":868.5,"size":27},
#           "counter_up":63,
#           "dev_eui":"0018b2000000113e",
#           "rx_time":1.497506024141793E9,
#           "encrypted_payload":"NtVcCKcPSpSh27ib03A="},
#        "jsonrpc":"2.0",
#        "id":"0b7dfe7ca183",
#        "method":"uplink"}]}

import sys
import json

default_location =  { 'lat': 52.2053,
                      'lng': 0.1183,
                      'alt': 0,
                      'error': 20000
                    }

def process_adeunis_test(line):
    sensor_data = json.loads(line)
    print(sensor_data["ts"])

for line in sys.stdin:
    process_adeunis_test(line)

