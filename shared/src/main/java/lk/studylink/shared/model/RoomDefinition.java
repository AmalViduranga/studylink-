package lk.studylink.shared.model;

import java.util.List;
import java.util.Objects;

/**
 * Immutable definition of a predefined classroom study room (SRS Section 1 & Section 6.4).
 *
 * @param id          stable room identifier (e.g. "netprog", "revision", "workshop")
 * @param displayName visible room title shown to students
 */
public record RoomDefinition(String id, String displayName) {

    public RoomDefinition {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        id = id.trim();
        displayName = displayName.trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("Room id must not be blank");
        }
        if (displayName.isEmpty()) {
            throw new IllegalArgumentException("Room displayName must not be blank");
        }
    }

    /**
     * Returns the three SRS baseline rooms in standard display order.
     */
    public static List<RoomDefinition> defaultRooms() {
        return List.of(
                new RoomDefinition("netprog", "Network Programming"),
                new RoomDefinition("revision", "Exam Revision"),
                new RoomDefinition("workshop", "Project Workshop")
        );
    }
}
