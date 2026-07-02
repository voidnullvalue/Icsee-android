package com.voidnullvalue.icseelocal.ptz

/** Note the spelling is "ZoomTile", not "ZoomTele" -- verbatim per the task brief. */
enum class PtzCommand(val wireValue: String) {
    DIRECTION_UP("DirectionUp"),
    DIRECTION_DOWN("DirectionDown"),
    DIRECTION_LEFT("DirectionLeft"),
    DIRECTION_RIGHT("DirectionRight"),
    DIRECTION_LEFT_UP("DirectionLeftUp"),
    DIRECTION_LEFT_DOWN("DirectionLeftDown"),
    DIRECTION_RIGHT_UP("DirectionRightUp"),
    DIRECTION_RIGHT_DOWN("DirectionRightDown"),
    ZOOM_TILE("ZoomTile"),
    ZOOM_WIDE("ZoomWide"),
    FOCUS_NEAR("FocusNear"),
    FOCUS_FAR("FocusFar"),
    IRIS_SMALL("IrisSmall"),
    IRIS_LARGE("IrisLarge"),
    SET_PRESET("SetPreset"),
    GOTO_PRESET("GotoPreset"),
    CLEAR_PRESET("ClearPreset"),
    START_TOUR("StartTour"),
    STOP_TOUR("StopTour"),
    ;

    /** Tour = 1 for commands containing "Tour", 0 otherwise -- per the task brief's parameter rules. */
    val isTour: Boolean get() = wireValue.contains("Tour")
}
