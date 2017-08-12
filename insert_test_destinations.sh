#!/bin/bash

# Author ijl20 (Ian Lewis)
# This script creates N test entries in the csn

database="tfcserver"
tablename="csn_destination"
sensor_type="test"
destination_id_start="test_destination_"
count=$1
if [ -z "$1" ]
then
    count=9
fi

(
# INSERT csn_destination rows
for i in $(seq 1 $count)
do
  destination_id=$destination_id_start$i

  echo INSERT INTO $tablename \(info\) VALUES \(\'{ \"destination_id\": \"$destination_id\", \"url\": \"http://spoofhost:7890/dir1/dir2/$i\"}\'\)';'

done
) | sudo -u postgres psql -d $database -v "ON_ERROR_STOP=1" >/dev/null

psql_exit=$?
if (( psql_exit > 0 ))
then
    echo Error $psql_exit
fi


