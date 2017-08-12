## VertxPG / MsgRouter

A vertx module to access PostgreSQL / PostGIS

This project was created to provide a development/test framework for bridging
tfc_web (Django) to tfc_server (Vertx) with the use of a shared PostgreSQL
database.

The tfc_server module MsgRouter needs to read the tables csn_sensor and csn_destination
at startup time to initialize it's mapping tables for sensor_id -> destination URL.

