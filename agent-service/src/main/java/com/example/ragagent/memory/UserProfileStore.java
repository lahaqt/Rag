package com.example.ragagent.memory;

import java.util.Map;

public interface UserProfileStore {
    UserProfile load(String userId);

    void merge(String userId, Map<String, String> facts);

    default boolean forget(String userId) {
        return false;
    }
}
