package org.wlou.jbdownloader.gui.controls;

import com.pixelduke.javafx.validation.ValidatorBase;
import javafx.scene.control.TextInputControl;

import java.net.MalformedURLException;
import java.net.URL;

/**
 * Created by Artem on 18.10.2015.
 */
public class UrlValidator extends ValidatorBase {

    @Override
    public void eval() {
        if(srcControl.get() instanceof TextInputControl) {
            TextInputControl textField = (TextInputControl) srcControl.get();
            String text = textField.getText();
            try {
                validUrl = new URL(text);
            } catch (MalformedURLException e) {
                hasErrors.set(true);
                return;
            }
            hasErrors.set(false);
        }
    }

    public void reset() {
        validUrl = null;
        hasErrors.set(false);
    }

    public URL getValidUrl() {
        return validUrl;
    }

    private URL validUrl;
}
