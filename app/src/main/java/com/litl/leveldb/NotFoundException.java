package com.litl.leveldb;

public class NotFoundException extends LevelDBException {
    private static final long serialVersionUID = 6207999645579440001L;

    public NotFoundException() {
    }

    public NotFoundException(String message) {
        super(message);
    }
}
