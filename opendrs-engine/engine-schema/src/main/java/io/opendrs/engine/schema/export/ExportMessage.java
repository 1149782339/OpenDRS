package io.opendrs.engine.schema.export;

import java.util.LinkedList;
import java.util.List;

public class ExportMessage {
    final private List<String> infos;

    final private List<String> errors;

    public ExportMessage() {
        this.infos = new LinkedList<>();
        this.errors = new LinkedList<>();
    }
    public List<String> getInfoMessages() {
        return infos;
    }

    public List<String> getErrorMessages() {
        return errors;
    }

    public void addErrorMessage(String message) {
        errors.add(message);
    }

    public void addInfoMessage(String message) {
        infos.add(message);
    }

}
