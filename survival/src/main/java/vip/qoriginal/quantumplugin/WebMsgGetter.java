package vip.qoriginal.quantumplugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.TimerTask;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WebMsgGetter extends TimerTask{
    static ArrayList<String> buffer = new ArrayList<>();
    @Override
    public void run(){
        try {
            String response = Request.sendGetRequest(Config.INSTANCE.getAPI_ENDPOINT() +"/qo/msglist/download").get();
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
                msg = processMessage(msg);
                if (msg.startsWith("[QQ]")) {
                    Component msgComponent = Component.text(msg).color(TextColor.color(113, 159, 165));
                    for(Player player : Bukkit.getOnlinePlayers()) player.sendMessage(msgComponent);
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

    static String processMessage(String msg) {
        // 替换 [CQ:file]
        Pattern filePattern = Pattern.compile("\\[CQ:file,[^\\]]*\\]");
        Matcher fileMatcher = filePattern.matcher(msg);
        msg = fileMatcher.replaceAll("[图片]");
        // 替换 [CQ:at]
        Pattern atPattern = Pattern.compile("\\[CQ:at,qq=(\\d+)\\]");
        Matcher atMatcher = atPattern.matcher(msg);
        msg = atMatcher.replaceAll("@$1");

        return msg;
    }
}
