/*
 Copyright (C) 2016 Lightbend, Inc <http://www.lightbend.com>
 */
define([
  "commons/websocket",
  "commons/settings"
], function(
  websocket,
  settings
) {

  var stream = websocket.subscribe('type', 'monitoring');

  var NO_MONITORING = 'Disabled';

  // Set up default solutions
  var monitoringSolutions = ko.observableArray([NO_MONITORING]);
  var monitoringSolution = settings.observable("build.monitoringSolution-"+serverAppModel.id, NO_MONITORING);
  var isPlayApplication = ko.observable(false); // used to determine what runner to execute (see runCommand below)

  var prependCommand = ko.computed(function() {
    return "";
  });

  return {
    stream                : stream,
    NO_MONITORING         : NO_MONITORING,
    monitoringSolutions   : monitoringSolutions,
    monitoringSolution    : monitoringSolution,
    prependCommand        : prependCommand,
    isPlayApplication     : isPlayApplication
  }
});
