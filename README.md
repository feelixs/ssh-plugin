# ssh-plugin

![Build](https://github.com/feelixs/ssh-plugin/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/27192.svg)](https://plugins.jetbrains.com/plugin/27192)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/27192.svg)](https://plugins.jetbrains.com/plugin/27192)

## About

<!-- Plugin description -->
Adds a toolbar to the IDE where you can store your SSH connection details, and easily initiate SSH Connections to them using the IDE integrated terminal.
- Store your connection details (host, password/key, etc) across IDE sessions.
- Automate your login workflow with "On Connect" initialization commands.
- Connect to your remote servers in one click!
<!-- Plugin description end -->


## Installation

- Using the IDE built-in plugin system:
  
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "ssh-plugin"</kbd> >
  <kbd>Install</kbd>
  
- Using JetBrains Marketplace:

  Go to [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID) and install it by clicking the <kbd>Install to ...</kbd> button in case your IDE is running.

  You can also download the [latest release](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID/versions) from JetBrains Marketplace and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

- Manually:

  Download the [latest release](https://github.com/feelixs/ssh-plugin/releases/latest) and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>


## Using the Plugin

1. Launch your IDE and install the plugin. It may prompt you to restart the IDE<br>
2. Navigate to the SSH Connections tab on the left bar<br>
   <img src="https://github.com/feelixs/ssh-plugin/blob/main/img/step1.PNG?raw=true"><br>
3. Click 'Add Connection' and enter your details. Press OK, and the IDE will remember these details across sessions.<br>
   <img src="https://github.com/feelixs/ssh-plugin/blob/main/img/step2.PNG?raw=true"><br>
4. With your newly added connection selected in the SSH Connection toolbar, press Connect<br>
   <img src="https://github.com/feelixs/ssh-plugin/blob/main/img/step3.PNG?raw=true"><br>
5. Now you can initiate a Terminal session to your SSH connection in a single click!

---
Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
[docs:plugin-description]: https://plugins.jetbrains.com/docs/intellij/plugin-user-experience.html#plugin-description-and-presentation
