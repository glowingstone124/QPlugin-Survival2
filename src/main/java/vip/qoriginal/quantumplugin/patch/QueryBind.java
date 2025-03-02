package vip.qoriginal.quantumplugin.patch;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import vip.qoriginal.quantumplugin.BindResponse;
import vip.qoriginal.quantumplugin.Config;
import vip.qoriginal.quantumplugin.Request;


public class QueryBind {
    public static JsonObject PlayerinfoObj = new JsonObject();

    public static BindResponse queryPlayer(String name) throws Exception {
        String result = Request.sendGetRequest(Config.INSTANCE.getAPI_ENDPOINT() + "/qo/download/registry?name=" + name).get();
        BindResponse relationship = new Gson().fromJson(result, BindResponse.class);
        return relationship;
    }
}
