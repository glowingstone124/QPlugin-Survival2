package vip.qoriginal.quantumplugin;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.TimerTask;

public class WebMsgGetter extends TimerTask{
    static ArrayList<String> buffer = new ArrayList<>();
    @Override
    public void run(){
            try {
                String response = Request.sendGetRequest("http://qoriginal.vip:8080/qo/msglist/download");
                JSONObject remoteObj = new JSONObject(response);
                JSONArray remoteRaw = remoteObj.getJSONArray("messages");
                ArrayList<String> remoteArr = parseArrList(remoteRaw);
                ArrayList<String> newMessages = new ArrayList<>();
                if (buffer == null || diff(remoteArr, buffer)) {
                    newMessages = new ArrayList<>(remoteArr);
                    if (!newMessages.isEmpty()) {
                        newMessages.removeAll(buffer);
                    }
                }
                for (String msg : newMessages) {
                    if (!msg.startsWith("[SERVER]")) {
                        for(Player player : Bukkit.getOnlinePlayers()) player.sendMessage(msg);
                    }
                }
                buffer = remoteArr;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
    }

    static ArrayList<String> parseArrList(JSONArray arr) {
        ArrayList<String> ret = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            ret.add(arr.getString(i));
        }
        return ret;
    }

    static boolean diff(ArrayList<String> arr1, ArrayList<String> arr2) {
        if (arr1.size() != arr2.size()) {
            return true;
        }
        for (int i = 0; i < arr1.size(); i++) {
            if (!arr1.get(i).equals(arr2.get(i))) {
                return true;
            }
        }
        return false;
    }
}
