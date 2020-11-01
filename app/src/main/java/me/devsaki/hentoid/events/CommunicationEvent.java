package me.devsaki.hentoid.events;

import androidx.annotation.Nullable;

public class CommunicationEvent {

    public static final int EV_SEARCH = 1;
    public static final int EV_ADVANCED_SEARCH = 2;
    public static final int EV_UPDATE_SORT = 3;
    public static final int EV_CLOSED = 3;

    public static final int RC_GROUPS = 1;
    public static final int RC_CONTENTS = 2;
    public static final int RC_DRAWER = 2;

    private final int type;
    private final int recipient;
    private final String message;

    public CommunicationEvent(int eventType, int recipient, @Nullable final String message) {
        this.type = eventType;
        this.recipient = recipient;
        this.message = message;
    }

    public int getType() {
        return type;
    }

    public int getRecipient() {
        return recipient;
    }

    @Nullable
    public String getMessage() {
        return message;
    }
}
