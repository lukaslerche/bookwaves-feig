package de.bookwaves;

import de.bookwaves.tag.Tag;

import java.util.List;

/**
 * Response for inventory operations containing tags with full decoded information.
 */
public class InventoryResponse {
    private boolean success;
    private String message;
    private long tagCount;
    private List<Tag> tags;

    public InventoryResponse(boolean success, String message, long tagCount, List<Tag> tags) {
        this.success = success;
        this.message = message;
        this.tagCount = tagCount;
        this.tags = tags;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    public long getTagCount() {
        return tagCount;
    }

    public List<Tag> getTags() {
        return tags;
    }
}
