package vertx_pg;

// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************
// VertxPg2.java
//
// This module receives messages from the EventBus and POSTs them on to application destinations
//
// Author: Ian Lewis ijl20@cam.ac.uk
//
// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;

import io.vertx.ext.jdbc.JDBCClient;
import io.vertx.ext.sql.SQLConnection;
import io.vertx.ext.sql.ResultSet;

import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpClientRequest;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;

public class VertxPg2 extends AbstractVerticle {

    private final String VERSION = "0.05";
    
    // from config()
    public int LOG_LEVEL;             // optional in config(), defaults to Constants.LOG_INFO
    private String MODULE_NAME;       // config module.name - normally "msgrouter"
    private String MODULE_ID;         // config module.id - unique for this verticle
    private String EB_SYSTEM_STATUS;  // config eb.system_status
    private String EB_MANAGER;        // config eb.manager
    
    private final int SYSTEM_STATUS_PERIOD = 10000; // publish status heartbeat every 10 s
    private final int SYSTEM_STATUS_AMBER_SECONDS = 25;
    private final int SYSTEM_STATUS_RED_SECONDS = 35;

    private EventBus eb = null;
    private Log logger;
        
    private ArrayList<JsonObject> START_ROUTERS; // config msgrouters.routers parameters

    // global vars
    private HashMap<String,HttpClient> http_clients; // used to store a HttpClient for each feed_id

    private HashMap<String,LoraDevice> lora_devices; // stores dev_eui -> app_eui mapping

    private HashMap<String,LoraApplication> lora_applications; // stores app_eui -> http POST mapping

    @Override
    public void start(Future<Void> fut) throws Exception {
      
        // load initialization values from config()
        if (!get_config())
            {
                Log.log_err("MsgRouter."+ MODULE_ID + ": failed to load initial config()");
                vertx.close();
                return;
            }

        logger = new Log(LOG_LEVEL);
    
        logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+": Version "+VERSION+" started with log_level "+LOG_LEVEL);

        eb = vertx.eventBus();

        // create holder for HttpClients, one per router
        http_clients = new HashMap<String,HttpClient>();

        // create holders for LoraWAN device and application data
        lora_devices = new HashMap<String,LoraDevice>();
        lora_applications = new HashMap<String,LoraApplication>();

        vertx.executeBlocking(load_fut -> {
                    load_data(load_fut);
                },
                res -> { logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                                  "load_data() complete, status "+res);
                       });

        // iterate through all the routers to be started
        for (int i=0; i<START_ROUTERS.size(); i++)
            {
                start_router(START_ROUTERS.get(i));
            }

        // **********************************************************************************
        // Subscribe to 'manager' messages, e.g. to add devices and applications
        //
        // For the message to be processed by this module, it must be sent with this module's
        // MODULE_NAME and MODULE_ID in the "to_module_name" and "to_module_id" fields. E.g.
        // {
        //    "msg_type":    "module_method",
        //    "to_module_name": "msgrouter",
        //    "to_module_id": "test",
        //    "method": "add_device",
        //    "params": { "dev_eui": "0018b2000000113e",
        //                "app_eui": "0018b2000000abcd"
        //                }
        // }
        eb.consumer(EB_MANAGER, message -> {
                manager_message(message);
            });

        // **********************************************************************************
        // send system status message from this module (i.e. to itself) immediately on startup, then periodically
        send_status();     
        // send periodic "system_status" messages
        vertx.setPeriodic(SYSTEM_STATUS_PERIOD, id -> { send_status();  });

    } // end start()

    private boolean load_data(Future<Object> fut)
    {
        JsonObject sql_client_config = new JsonObject()
            .put("url", "jdbc:postgresql:csn")
            .put("user", "ijl20")
            .put("password", "foo!1")
            .put("driver_class", "org.postgresql.Driver");

        //SQLClient sql_client = PostgreSQLClient.createShared(vertx, sql_client_config);
        JDBCClient jdbc_client = JDBCClient.createShared(vertx, sql_client_config);

        logger.log(Constants.LOG_DEBUG, "VertxPg jdbc_client created.");

        jdbc_client.getConnection(res -> {
            if (res.failed()) {
                logger.log(Constants.LOG_WARN, "VertxPg getConnection failed.");
                fut.fail(res.cause());
            } else {
                logger.log(Constants.LOG_DEBUG, "VertxPg getConnection succeeded.");

                SQLConnection sql_connection = res.result();

                String sql_query = "SELECT * FROM csn_lwdevice";

                sql_connection.query( sql_query,
                     r -> {
                              if (r.failed()) 
                              {
                                  fut.fail(r.cause());
                                  return;
                              }

                              logger.log(Constants.LOG_DEBUG, r.result().getNumRows() + " rows returned");

                              for (JsonObject row : r.result().getRows())
                              {
                                  logger.log(Constants.LOG_DEBUG, row.toString());
                              }

                              // close connection to database
                              sql_connection.close(v -> {
                                  logger.log(Constants.LOG_DEBUG, "VertxPg sql_connection closed.");
                                  fut.complete("ok");
                              });
                          });
            }
        });

        return true;
    }

    // Here is where we process the 'manager' messages received for this module on the
    // config 'eb.manager' eventbus address.
    // e.g. the 'add_device' and 'add_application' messages.
    private void manager_message(Message<java.lang.Object> message)
    {
        JsonObject msg = new JsonObject(message.body().toString());

        // decode who this 'manager' message was sent to
        String to_module_name = msg.getString("to_module_name");
        String to_module_id = msg.getString("to_module_id");

        // *********************************************************************************
        // Skip this message if it has the wrong module_name/module_id
        if (to_module_name == null || !(to_module_name.equals(MODULE_NAME)))
        {
            logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                       ": skipping manager message (not for this module_name) on "+EB_MANAGER);
            return;
        }
        if (to_module_id == null || !(to_module_id.equals(MODULE_ID)))
        {
            logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                       ": skipping manager message (not for this module_id) on "+EB_MANAGER);
            return;
        }

        // *********************************************************************************
        // Process the manager message

        // ignore the message if it has no 'method' property
        String method = msg.getString("method");
        if (method == null)
        {
            logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                       ": skipping manager message ('method' property missing) on "+EB_MANAGER);
            return;
        }
        
        switch (method)
        {
            case Constants.METHOD_ADD_DEVICE:
                logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                           ": received add_device manager message on "+EB_MANAGER);
                JsonObject dev_info = msg.getJsonObject("params");
                if (dev_info == null)
                {
                    logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                               ": skipping manager message ('params' property missing) on "+EB_MANAGER);
                    return;
                }
                add_device(dev_info);
                break;

            case Constants.METHOD_REMOVE_DEVICE:
                logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                           ": received remove_device manager message on "+EB_MANAGER);
                dev_info = msg.getJsonObject("params");
                if (dev_info == null)
                {
                    logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                               ": skipping manager message ('params' property missing) on "+EB_MANAGER);
                    return;
                }
                remove_device(dev_info);
                break;

            case Constants.METHOD_ADD_APPLICATION:
                logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                           ": received add_application manager message on "+EB_MANAGER);
                JsonObject app_info = msg.getJsonObject("params");
                if (app_info == null)
                {
                    logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                               ": skipping manager message ('params' property missing) on "+EB_MANAGER);
                    return;
                }
                add_application(app_info);
                break;

            case Constants.METHOD_REMOVE_APPLICATION:
                logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                           ": received remove_application manager message on "+EB_MANAGER);
                app_info = msg.getJsonObject("params");
                if (app_info == null)
                {
                    logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                               ": skipping manager message ('params' property missing) on "+EB_MANAGER);
                    return;
                }
                remove_application(app_info);
                break;

            default:
                logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                           ": received unrecognized 'method' in manager message on "+EB_MANAGER+": "+method);
                break;
        }

    }

    // Add a LoraWAN device to lora_devices, having received an 'add_device' manager message
    private void add_device(JsonObject params)
    {
        String dev_eui = params.getString("dev_eui");
        if (dev_eui == null)
        {
            logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                       ": skipping add_device manager message ('dev_eui' property missing) on "+EB_MANAGER);
            return;
        }
        
        // create a LoraDevice object for this device
        LoraDevice device = new LoraDevice(params);

        // add the device to the current list (HashMap)
        lora_devices.put(dev_eui, device);

        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                   ": device count now "+lora_devices.size());
    }

    // Remove a LoraWAN device from lora_devices, having received a 'remove_device' manager message
    private void remove_device(JsonObject params)
    {
        String dev_eui = params.getString("dev_eui");
        if (dev_eui == null)
        {
            logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                       ": skipping remove_device manager message ('dev_eui' property missing) on "+EB_MANAGER);
            return;
        }
        
        // remove the device from the current list (HashMap)
        lora_devices.remove(dev_eui);

        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                   ": device count now "+lora_devices.size());
    }

    // Add a LoraWAN application to lora_applications, having received an 'add_application' manager message
    private void add_application(JsonObject params)
    {
        String app_eui = json_property_to_string(params, "app_eui");

        if (app_eui == null)
        {
            logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                       ": skipping add_application manager message ('app_eui' property missing) on "+EB_MANAGER);
            return;
        }

        // Create LoraApplication object for this application
        LoraApplication application = new LoraApplication(params);

        // Add to the current list (HashMap) of objects
        lora_applications.put(app_eui, application);

        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                   ": application count now "+lora_applications.size());
    }

    private void remove_application(JsonObject params)
    {
        String app_eui = json_property_to_string(params, "app_eui");

        if (app_eui == null)
        {
            logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                       ": skipping remove_application manager message ('app_eui' property missing) on "+EB_MANAGER);
            return;
        }

        // Remove from the current list (HashMap) of objects
        lora_applications.remove(app_eui);

        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                   ": application count now "+lora_applications.size());
    }
        
    // Return a String value for a JSONObject property that may be String or Integer.
    // This is used to bridge versions of tfc_web that may use either for 'app_eui'
    private String json_property_to_string(JsonObject jo, String property)
    {
        String string_value;
        try
        {
            string_value = jo.getString(property);
        }
        catch (java.lang.ClassCastException e)
        {
            string_value = jo.getInteger(property).toString();
        }
        return string_value;
    }

    // send UP status to the EventBus
    private void send_status()
    {
        eb.publish(EB_SYSTEM_STATUS,
                 "{ \"module_name\": \""+MODULE_NAME+"\"," +
                   "\"module_id\": \""+MODULE_ID+"\"," +
                   "\"status\": \"UP\"," +
                   "\"status_msg\": \"UP\"," +
                   "\"status_amber_seconds\": "+String.valueOf( SYSTEM_STATUS_AMBER_SECONDS ) + "," +
                   "\"status_red_seconds\": "+String.valueOf( SYSTEM_STATUS_RED_SECONDS ) +
                 "}" );
    }
    
    // ************************************************************
    // start_router()
    // start a Router by registering a consumer to the given address
    // ************************************************************
    private void start_router(JsonObject router_config)
    {

        // A router config() contains a minimum of a "source_address" property,
        // which is the EventBus address it will listen to for messages to be forwarded.
        //
        // Note: a router config() (in msgrouter.routers) MAY contain a filter, such as
        // "source_filter": { 
        //                    "field": "dev_eui",
        //                    "compare": "=",
        //                    "value": "0018b2000000113e"
        //                   }
        // in which case only messages on the source_address that match this pattern will
        // be processed.

        JsonObject filter_json = router_config.getJsonObject("source_filter");
        boolean has_filter =  filter_json != null;

        //final RouterFilter source_filter = has_filter ? new RouterFilter(filter_json) : null;

        final String config_app_eui = router_config.getString("app_eui");

        if (config_app_eui != null)
        {
            add_application(router_config);
        }

        //final HttpClient http_client = vertx.createHttpClient( new HttpClientOptions()
        //                                               .setSsl(router_config.getBoolean("http.ssl"))
        //                                               .setTrustAll(true)
        //                                               .setDefaultPort(router_config.getInteger("http.port"))
        //                                               .setDefaultHost(router_config.getString("http.host"))
        //                                                     );
        String router_filter_text;
        if (has_filter)
            {
                //source_filter = new RouterFilter(filter_json);  
                router_filter_text = " with " + filter_json.toString();
            }
        else
            {
                router_filter_text = "";
            }
        logger.log(Constants.LOG_INFO, MODULE_NAME+"."+MODULE_ID+
                   ": starting router "+router_config.getString("source_address")+ router_filter_text);

        // register to router_config.source_address,
        // test messages with router_config.source_filter
        // and call store_msg if current message passes filter
        eb.consumer(router_config.getString("source_address"), message -> {
            //System.out.println("MsgRouter."+MODULE_ID+": got message from " + router_config.source_address);
            JsonObject msg = new JsonObject(message.body().toString());
            
            //**************************************************************************
            //**************************************************************************
            // Route the message onwards via POST to destination
            //**************************************************************************
            //**************************************************************************
            if (!has_filter)// || source_filter.match(msg))
            {
                // route this message if it matches the filter within the RouterConfig
                //route_msg(http_client, router_config, msg);
                if (config_app_eui != null)
                {
                    logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                               ": sending message via config app_eui "+config_app_eui);
                    try 
                    {
                        // Careful here!! Although FeedHandler(etc) can send an Array of data points in
                        // the "request_data" parameter, for LoraWAN purposes we are currently assuming
                        // only a single data value is going to be present, hence we are forwarding
                        // msg.getJsonArray("request_data").getJsonObject(0), not the whole array.
                        lora_applications.get(config_app_eui).send(msg.getJsonArray("request_data").getJsonObject(0).toString());
                    }
                    catch (Exception e)
                    {
                        logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                                   ": send error for "+config_app_eui);
                    }
                    return;
                }
                else
                {
                    // There is no app_eui defined in the config(), so we'll try and route via
                    // the loraWAN dev_eui -> app_eui mapping in the lora_devices HashMap
                    String dev_eui = msg.getString("dev_eui");
                    if (dev_eui == null)
                    {
                        logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                                   ": skipping message (no dev_eui) ");
                        return;
                    }
                    logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                               ": using dev_eui "+dev_eui);

                    String app_eui;

                    try
                    {
                        app_eui = json_property_to_string(lora_devices.get(dev_eui).dev_info, "app_eui");
                    }
                    catch (Exception NullPointerException)
                    {
                        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                                   ": ignoring "+dev_eui+" no app_eui set");
                        return;
                    }

                    logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                               ": sending to app_eui "+app_eui);

                    lora_applications.get(app_eui).send(msg.getJsonArray("request_data").getJsonObject(0).toString());
                }
            }
            else
            {
                logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                     ": msg skipped no match "+router_config.getJsonObject("source_filter").toString());
            }

        });
    
    } // end start_router

    //**************************************************************************
    //**************************************************************************
    // Load initialization global constants defining this MsgRouter from config()
    //**************************************************************************
    //**************************************************************************
    private boolean get_config()
    {
        // config() values needed by all TFC modules are:
        //   module.name - usually "msgrouter"
        //   module.id - unique module reference to be used by this verticle
        //   eb.system_status - String eventbus address for system status messages
        //   eb.manager - eventbus address for manager messages
        
        MODULE_NAME = config().getString("module.name");
        if (MODULE_NAME == null)
        {
          Log.log_err("MsgRouter: module.name config() not set");
          return false;
        }
        
        MODULE_ID = config().getString("module.id");
        if (MODULE_ID == null)
        {
          Log.log_err("MsgRouter: module.id config() not set");
          return false;
        }

        LOG_LEVEL = config().getInteger(MODULE_NAME+".log_level", 0);
        if (LOG_LEVEL==0)
            {
                LOG_LEVEL = Constants.LOG_INFO;
            }
        
        EB_SYSTEM_STATUS = config().getString("eb.system_status");
        if (EB_SYSTEM_STATUS == null)
        {
          Log.log_err("MsgRouter."+MODULE_ID+": eb.system_status config() not set");
          return false;
        }

        EB_MANAGER = config().getString("eb.manager");
        if (EB_MANAGER == null)
        {
          Log.log_err("MsgRouter."+MODULE_ID+": eb.manager config() not set");
          return false;
        }

        // iterate through the msgrouter.routers config values
        START_ROUTERS = new ArrayList<JsonObject>();
        JsonArray config_router_list = config().getJsonArray(MODULE_NAME+".routers");
        for (int i=0; i<config_router_list.size(); i++)
            {
                JsonObject config_json = config_router_list.getJsonObject(i);

                // add MODULE_NAME, MODULE_ID to every RouterConfig
                config_json.put("module_name", MODULE_NAME);
                config_json.put("module_id", MODULE_ID);
                
                //RouterConfig router_config = new RouterConfig(config_json);
                
                START_ROUTERS.add(config_json);
            }

        return true;
    } // end get_config()

    // This class holds the LoraWAN device data
    // received in the 'params' property of the 'add_device' eventbus method message
    private class LoraDevice {
        public String dev_eui;
        public JsonObject dev_info;
        // e.g. {
        //        "dev_eui": "0018b2000000113e",
        //        "app_eui": "0018b2000000abcd"
        //      }

        // Constructor
        LoraDevice(JsonObject params)
        {
            dev_eui = params.getString("dev_eui");
            dev_info = params;
            logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                 ": added device "+this.toString());
        }

        public String toString()
        {
            return dev_eui + " -> " + json_property_to_string(dev_info, "app_eui");
        }
    }

    // This class holds the LoraWAN application (i.e. http destination) data
    // received in the 'params' property of the 'add_application' eventbus method message
    private class LoraApplication {
        public String app_eui;
        public JsonObject app_info;
        public HttpClient http_client;

        // e.g. {
        //        "app_eui": "0018b2000000abcd",
        //        "http.post": true,
        //        "http.host": "localhost",
        //        "http.port": 8098,
        //        "http.uri": "/everynet_feed/test/adeunis_test3",
        //        "http.ssl": false,
        //        "http.token": "test-msgrouter-post"
        //      }

        // Constructor
        LoraApplication(JsonObject params)
        {
            app_eui = json_property_to_string(params, "app_eui");

            app_info = params;
            http_client = vertx.createHttpClient( new HttpClientOptions()
                                                       .setSsl(params.getBoolean("http.ssl",false))
                                                       .setTrustAll(true)
                                                       .setDefaultPort(params.getInteger("http.port",80))
                                                       .setDefaultHost(params.getString("http.host"))
                                                );

            logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                 ": added application "+this.toString());
        }

        public String toString()
        {
            String http_token = app_info.getString("http.token","");

            return app_eui+" -> "+
                   "<"+http_token+"> "+
                   (app_info.getBoolean("http.ssl",false) ? "https://" : "http://")+
                   app_info.getString("http.host")+":"+
                   app_info.getInteger("http.port",80)+
                   app_info.getString("http.uri");
        }

        public void send(String msg)
        {
            logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                       ": sending to "+app_eui+": " + msg);

            String http_uri = app_info.getString("http.uri");

            try
            {
                HttpClientRequest request = http_client.post(http_uri, response -> {
                        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                                   ": msg posted to " + this.toString());

                        logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
                                   ": response was " + response.statusCode());

                    });

                request.exceptionHandler( e -> {
                        logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                                   ": LoraApplication HttpClientRequest error for "+app_eui);
                        });

                // Now do stuff with the request
                request.putHeader("content-type", "application/json");
                request.setTimeout(15000);

                String auth_token = app_info.getString("http.token");
                if (auth_token != null)
                    {
                        request.putHeader("X-Auth-Token", auth_token);
                    }
                // Make sure the request is ended when you're done with it
                request.end(msg);
            }
            catch (Exception e)
            {
                logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                           ": LoraApplication send error for "+app_eui);
            }
        }
            
    } // end class LoraApplication


} // end class MsgRouter

