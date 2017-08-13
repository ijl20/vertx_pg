#!/bin/bash

# Author ijl20 (Ian Lewis)
# This script creates N test entries in the csn

database="tfcserver"
tablename="csn_destination"
destination_id_start="test_destination_"
destination_type="everynet_jsonrpc"
destination_count=$1
if [ -z "$1" ]
then
    destination_count=5
fi

(
# assuming this is for testing within the ijl20 user
echo "GRANT SELECT ON TABLE csn_destination TO ijl20;"

# INSERT csn_destination rows
for i in $(seq 1 $destination_count)
do
  destination_id=$destination_id_start$i

  echo INSERT INTO $tablename \(info\) VALUES \(\'{ \"destination_id\": \"$destination_id\", \"url\": \"http://spoofhost:7890/dir1/dir2/$i\", \"destination_type\": \"$destination_type\"}\'\)';'

done
) | sudo -u postgres psql -d $database -v "ON_ERROR_STOP=1" >/dev/null

psql_exit=$?
if (( psql_exit > 0 ))
then
    echo Error $psql_exit
    exit $psql_exit
fi

echo $destination_count destinations inserted

