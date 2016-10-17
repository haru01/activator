/*
 Copyright (C) 2016 Lightbend, Inc <http://www.lightbend.com>
 */
define([
  "main/plugins",
  "services/sbt",
  "widgets/layout/layout",
  "services/monitoring/monitoringSolutions",
  "text!./run.html",
  'widgets/modals/modals',
  "css!./run",
  "css!widgets/buttons/switch",
  "css!widgets/buttons/button",
  "css!widgets/menu/menu",
  "css!widgets/buttons/select"
], function(
  plugins,
  sbt,
  layout,
  monitoringSolutions,
  tpl,
  modals
) {

  var subPlugin = ko.observable();
  var currentPlugin;
  var sbtExecCommand = function(cmd){
    sbt.tasks.requestExecution(cmd);
  }
  var mainRunAction = function() {
    if (sbt.tasks.pendingTasks.run()){
      sbt.tasks.actions.stopRun();
    } else {
      sbt.tasks.actions.run();
    }
  }
  var mainRunName = ko.computed(function() {
    if (sbt.tasks.pendingTasks.run()) {
      return sbt.tasks.pendingTasks.stoppingRun() ? "Stopping" : "Stop";
    } else {
      return "Run";
    }
  });

  var runEnabled = ko.computed(function() {
    if (sbt.tasks.applicationReady()) {
      return !sbt.tasks.pendingTasks.stoppingRun();
    } else {
      return false;
    }
  });

  var displayMains = ko.computed(function() {
    return (sbt.tasks.applicationReady() && sbt.app.currentMainClass() && !sbt.tasks.isPlayApplication());
  });

  var runDisabled = ko.computed(function() { return !runEnabled(); });
  var playUrl = sbt.tasks.playApplicationUrl;
  var displayPlayUrl = ko.computed(function() {
    if ((playUrl() !== null) && !sbt.tasks.pendingTasks.stoppingRun() && sbt.tasks.pendingTasks.run()) {
      return true;
    } else {
      return false;
    }
  });

  var State = {
    subPlugin: subPlugin,
    sbtExecCommand: sbtExecCommand,
    sbt: sbt,
    rerunOnBuild: sbt.app.settings.rerunOnBuild,
    showLogDebug: sbt.app.settings.showLogDebug,
    monitoringSolutions: monitoringSolutions,
    mainRunAction: mainRunAction,
    mainRunName: mainRunName,
    customCommands: sbt.app.customCommands,
    runEnabled: runEnabled,
    runDisabled: runDisabled,
    displayMains: displayMains,
    displayPlayUrl: displayPlayUrl,
    playUrl: playUrl
  }

  // Subplugins titles
  var subPlugins = {
    system:         "Stdout"
  }

  return {
    render: function(url) {
      layout.renderPlugin(ko.bindhtml(tpl, State))
    },
    route: plugins.route('run', function(url, breadcrumb, plugin) {
      subPlugin(plugin.render());
      currentPlugin = plugin;
      breadcrumb([['run/', "Run"],['run/'+url.parameters[0], subPlugins[url.parameters[0]]]]);
    }, "run/system"),

    keyboard: function(key, meta, e) {
      if (currentPlugin.keyboard) {
        currentPlugin.keyboard(key, meta, e);
      }
    }
  }
});
