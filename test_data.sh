#!/bin/bash

sensor_type=test

destination_id_start="test_destination_"

# INSERT csn_destination rows
for i in $(seq 1 9)
do
  destination_id=$destination_id_start$i

  echo INSERT INTO csn_destination \(info\) VALUES \(\'{ \"destination_id\": \"$destination_id\", \"url\": \"http://spoofhost:7890/dir1/dir2/$i\"}\'\)';'

done

#INSERT csn_sensor rows
for i in $(seq 1 10)
do
  sensor_id="test_sensor_"$((10000 + RANDOM % 9999))$i
  destination_id=$destination_start$((1 + RANDOM % 9))
  echo INSERT INTO csn_sensor \(info\) VALUES \(\'{ \"sensor_id\": \"$sensor_id\", \"destination_id\": \"$destination_id_start$destination_id\", \"sensor_type\": \"$sensor_type\"}\'\)';'
done

