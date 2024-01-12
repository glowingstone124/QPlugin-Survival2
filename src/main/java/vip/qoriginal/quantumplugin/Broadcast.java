package vip.qoriginal.quantumplugin;

import com.google.gson.JsonObject;

import static vip.qoriginal.quantumplugin.Config.BroadcastEndpoint;

public class Broadcast {
    private BroadcastMethod method;
    public Broadcast(BroadcastMethod bm) {
        method = bm;
    }
    public void sendBroadcast(String msg) throws Exception{
        if (method == BroadcastMethod.API){
            Request.sendPostRequest(String.format(BroadcastEndpoint, "post"), msg);
        }
    }
    public String getBroadcast() throws Exception{
        if (method == BroadcastMethod.API){
            String result = Request.sendGetRequest(String.format(BroadcastEndpoint, "get"));
            //TODO validate
            return result;
        }
        return null;
    }
    public enum BroadcastMethod{
        API,
        QQ,
        MORE
    }
}
