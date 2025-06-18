navigator.geolocation = {
    getCurrentPosition: function(success, error, options) {
        error({
            code: 1, // PERMISSION_DENIED
            message: "Geolocation not yet implemented."
        });
    },
    watchPosition: function(success, error, options) {
        error({
            code: 1, // PERMISSION_DENIED
            message: "Geolocation not yet implemented."
        });
        return null; // No watch ID since we don't support it
    },
    clearWatch: function(watchId) {

    }
}