package fish.payara.extensions.autoscale.groups.aws.admingui;

import org.glassfish.api.admingui.ConsoleProvider;
import org.jvnet.hk2.annotations.Service;

import java.net.URL;

/**
 *  This class serves as a marker to indicate this OSGi bundle provides GUI content which is to be displayed in the
 *  GlassFish admin console.  The {@link #getConfiguration()} method should either return <code>(null)</code>, or a
 *  <code>URL</code> to the <code>console-config.xml</code> file.
 *
 *  @author Ken Paulsen (ken.paulsen@sun.com)
 */
@Service
public class AWSScaleGroupsConsolePlugin implements ConsoleProvider {

    @Override
    public URL getConfiguration() {
        return null;
    }
}
