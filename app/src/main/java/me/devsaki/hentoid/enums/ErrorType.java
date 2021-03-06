package me.devsaki.hentoid.enums;

import io.objectbox.converter.PropertyConverter;

public enum ErrorType {

    PARSING(0, "Parsing"),
    NETWORKING(1, "Networking"),
    IO(2, "I/O"),
    CAPTCHA(3, "Captcha"),
    IMG_PROCESSING(4, "Image processing"),
    SITE_LIMIT(5, "Downloads/bandwidth limit reached"),
    ACCOUNT(6, "No account or insufficient credentials"),
    IMPORT(7, "No local file found after import"),
    WIFI(8, "Book skipped because of wi-fi download size limitations"),
    BLOCKED(9, "Book contains a blocked tag"),
    UNDEFINED(99, "Undefined");

    private final int code;
    private final String name;

    ErrorType(int code, String name) {
        this.code = code;
        this.name = name;
    }

    public static ErrorType searchByCode(int code) {
        for (ErrorType entry : ErrorType.values()) {
            if (entry.getCode() == code)
                return entry;
        }
        return UNDEFINED;
    }

    private int getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public static class ErrorTypeConverter implements PropertyConverter<ErrorType, Integer> {
        @Override
        public ErrorType convertToEntityProperty(Integer databaseValue) {
            if (databaseValue == null) {
                return null;
            }
            for (ErrorType type : ErrorType.values()) {
                if (type.getCode() == databaseValue) {
                    return type;
                }
            }
            return ErrorType.UNDEFINED;
        }

        @Override
        public Integer convertToDatabaseValue(ErrorType entityProperty) {
            return entityProperty == null ? null : entityProperty.getCode();
        }
    }
}
