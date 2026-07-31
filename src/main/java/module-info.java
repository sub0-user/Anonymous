module org.server.anonymous {
    requires javafx.controls;
    requires javafx.fxml;
    requires kotlin.stdlib;

    opens org.server.anonymous to javafx.fxml;
    opens org.server.anonymous.controller to javafx.fxml;
    exports org.server.anonymous;
}
