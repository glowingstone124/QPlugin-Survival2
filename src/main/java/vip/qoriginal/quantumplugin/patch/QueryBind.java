package vip.qoriginal.quantumplugin.patch;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.bukkit.entity.Player;
import vip.qoriginal.quantumplugin.BindResponse;
import vip.qoriginal.quantumplugin.Request;

import java.util.Arrays;

import static vip.qoriginal.quantumplugin.JoinLeaveListener.prolist;

public class QueryBind {
    public static JsonObject PlayerinfoObj = new JsonObject();

    public static JsonObject queryPlayer(String name) throws Exception {
        String result = Request.sendGetRequest("http://127.0.0.1:8080/qo/download/registry?name=" + name);
        BindResponse relationship = new Gson().fromJson(result, BindResponse.class);
        if (relationship.code == 0 && !Arrays.asList(prolist).contains(name)) {
            PlayerinfoObj.addProperty("name", name);
            PlayerinfoObj.addProperty("qq", relationship.qq);
        } else if (relationship.code == 0) {
            PlayerinfoObj.addProperty("name", name);
            PlayerinfoObj.addProperty("qq", -1);
        } else {
            PlayerinfoObj.addProperty("name", "You should not see this because this name is too long to be seen");
        }

        return PlayerinfoObj;
    }
}
