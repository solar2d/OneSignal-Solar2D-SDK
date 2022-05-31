package com.onesignal;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public class FunctionExposer {
    public static Map<String, Object> jsonObjectToMap(JSONObject json) throws JSONException {
        return JSONUtils.jsonObjectToMap(json);
    }

    public static List<Object> jsonArrayToList(JSONArray array) throws JSONException {
        return JSONUtils.jsonArrayToList(array);
    }

    public static Collection<String> extractStringsFromCollection(Collection<Object> collection) {
        return OSUtils.extractStringsFromCollection(collection);
    }

}
