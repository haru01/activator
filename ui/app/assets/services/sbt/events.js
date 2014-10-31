define([
  './app',
  './tasks',
  'services/ajax',
  'main/router',
  'commons/websocket'
],function(
  app,
  tasks,
  fs,
  router,
  websocket
) {

  /**
  Error counters
  */
  var errorCounters = {
    build: ko.observable(0),
    code:  ko.observable(0),
    run:   ko.observable(0),
    test:  ko.observable(0)
  }

  /**
  Notification list
  */
  var notifications = ko.observableArray([]);

  /**
  Notification object constructor
  */
  function notify(execution) {

    if (!execution.succeeded()){
      if ((execution.commandId == "run") && router.current().id != "run"){
        errorCounters.run(errorCounters.run()+1);
        new Notification("Runtime error", "#run/", "run", execution);
      } else if (execution.commandId == "test"){
        // Only show notification if we don't see the result
        if (router.current().id != "test") {
          new Notification("Test failed", "#test/results", "test", execution);
        }
      } else if (router.current().id != "build"){
        if (execution.compilationErrors.length){
          var url = "#code"+ fs.relative(ers[0].position.sourcePath)+":"+execution.compilationErrors[0].position.line;
          new Notification("Compilation error", url, "code", execution);
        } else {
          new Notification("Build error", "#build/tasks/"+execution.executionId, "build", execution);
        }
      } else {
        new Notification("Unknown error", "#build/tasks/"+execution.executionId, "unknown", execution);
      }
    }
  }

  var readNotifications = ko.observableArray([]);

  var unreadBuildErrors = ko.computed(function() {
    return tasks.executions().filter(function(execution) {
      return execution.finished() && !execution.succeeded() && readNotifications().indexOf(execution.executionId);
    });
  });

  function Notification(text, link, type, execution) {
    this.text = text;
    this.link = link;
    this.type = type;
    this.execution = execution;
    this.read = ko.observable(false);
    notifications.unshift(this);
  }

  /**
  Update counters, run auto-commands (eg: test on compile)
  */
  tasks.ProcessedExecutionsStream.each(function(execution) {
    // Update Tests counters
    if (execution.testResults){
      errorCounters.test(execution.testResults.filter(function(t) {
        return t.outcome == "failed";
      }).length);
    }

    // Update Compile/Code counter
    errorCounters.code(execution.compilationErrors.filter(function(m) {
      return m.severity == "Error";
    }).length);

    // // Failed tasks (Build counter)
    // if (!execution.succeeded() && router.current().id != "build"){
    //   errorCounters.build(errorCounters.build()+1);
    // }

    // Run auto-commands
    if (execution.succeeded() && execution.command == "compile") {
      if (app.settings.rerunOnBuild() && tasks.applicationReady()){
        debug && console.log("app.rerunOnBuild is on: Requesting 'run' task");
        tasks.actions.kill();
        tasks.actions.run();
      }
      if (app.settings.retestOnSuccessfulBuild()){
        debug && console.log("app.retestOnSuccessfulBuild is on: Requesting 'test' task")
        tasks.actions.test();
      }
    }

  });

  websocket.subscribe({ type:'sbt', subType:'ProjectFilesChanged' })
    .each(function() {
      if (app.settings.recompileOnChange()){
        debug && console.log("app.rerunOnBuild is on: Requesting 'compile' task")
        tasks.actions.compile();
      }
    });

  return {
    errorCounters:      errorCounters,
    notifications:      notifications,
    unreadBuildErrors:  unreadBuildErrors
  }

})
