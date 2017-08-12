#!/bin/bash

# Author ijl20 (Ian Lewis)
# This script creates N test entries in the csn

database="tfcserver"
tablename="csn_sensor"
sensor_type="test"
destination_id_start="test_destination_"
count=$1
if [ -z "$1" ]
then
    count=10
fi

(
#INSERT csn_sensor rows
for i in $(seq 1 $count)
do
  sensor_id="test_sensor_"$((1000000 + 1000*(RANDOM % 999) + (RANDOM % 999)))$i
  destination_id=$destination_start$((1 + RANDOM % 9))
  echo INSERT INTO $tablename \(info\) VALUES \(\'{ \"sensor_id\": \"$sensor_id\", \"destination_id\": \"$destination_id_start$destination_id\", \"sensor_type\": \"$sensor_type\"}\'\)';'
done
) | sudo -u postgres psql -d $database -v "ON_ERROR_STOP=1" >/dev/null

psql_exit=$?
if (( psql_exit > 0 ))
then
    echo Error $psql_exit
fi

