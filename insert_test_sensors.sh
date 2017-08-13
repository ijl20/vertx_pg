#!/bin/bash

# Author ijl20 (Ian Lewis)
# This script creates N test entries in the csn

database="tfcserver"
tablename="csn_sensor"
sensor_type="test"
sensor_count=$1
if [ -z "$1" ]
then
    sensor_count=20
fi
destination_id_start="test_destination_"
destination_type="everynet_jsonrpc"
destination_count=$2
if [ -z "$2" ]
then
    destination_count=5
fi

(

echo "GRANT SELECT ON TABLE csn_sensor TO ijl20;"

#INSERT csn_sensor rows
for i in $(seq 1 $sensor_count)
do
  sensor_id="test_sensor_"$((1000000 + 1000*(RANDOM % 999) + (RANDOM % 999)))$i
  destination_id=$destination_start$((1 + RANDOM % $destination_count))
  echo INSERT INTO $tablename \(info\) VALUES \(\'{ \"sensor_id\": \"$sensor_id\", \"destination_id\": \"$destination_id_start$destination_id\", \"sensor_type\": \"$sensor_type\", \"destination_type\": \"$destination_type\"}\'\)';'
done
) | sudo -u postgres psql -d $database -v "ON_ERROR_STOP=1" >/dev/null

psql_exit=$?
if (( psql_exit > 0 ))
then
    echo Error $psql_exit
    exit $psql_exit
fi

echo $sensor_count sensors loaded with $destination_count destinations

