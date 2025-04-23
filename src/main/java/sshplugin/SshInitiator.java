package sshplugin;

import com.intellij.openapi.project.Project;
import com.intellij.terminal.TerminalShellCommandHandler;
import org.jetbrains.annotations.NotNull;

public class SshInitiator implements TerminalShellCommandHandler {
    private final Project project;
    private final String host;
    private final String username;
    private final int port;

    public SshInitiator(Project project, String host, String username, int port) {
        this.project = project;
        this.host = host;
        this.username = username;
        this.port = port;
    }

    @Override
    public boolean execute(@NotNull String command) {
        if (!command.startsWith("ssh ")) return false;
        
        // TODO: Add password injection and sudo handling
        return true;
    }

    public static void initiateConnection(Project project, String host, String username, int port) {
        new SshInitiator(project, host, username, port);
    }
