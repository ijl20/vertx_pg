#!/bin/bash
#
java -cp "target/vertx_pg-1.0-SNAPSHOT-fat.jar:postgresql-42.1.3.jar" io.vertx.core.Launcher run "service:msgrouter_pg.test"

