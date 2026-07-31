module org.server.anonymous {
    requires javafx.controls;
    requires javafx.fxml;
    requires kotlin.stdlib;


    opens org.server.anonymous to javafx.fxml;
    exports org.server.anonymous;
}