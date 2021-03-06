/**
 * Copyright (c) Codice Foundation
 *
 * This is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. A copy of the GNU Lesser General Public License
 * is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 *
 **/
/*global define , window*/
define([
  'backbone',
  'underscore',
  'jquery',
  'js/wreqr'
], function (Backbone, _, $, wreqr) {

  var Installer = {};

  var _step = function (direction) {
    var changeObj = {};
    changeObj.stepNumber = this.get('stepNumber') + direction;
    if (changeObj.stepNumber < this.get('totalSteps')) {
      changeObj.hasNext = true;
    } else {
      changeObj.stepNumber = this.get('totalSteps');
      changeObj.hasNext = false;
    }

    if (changeObj.stepNumber > 0) {
      if (changeObj.stepNumber < this.get('totalSteps')) {
        changeObj.hasPrevious = true;
      } else {
        changeObj.hasPrevious = false;
      }
    } else {
      changeObj.stepNumber = 0;
      changeObj.hasPrevious = false;
    }

    return changeObj;
  };

  Installer.Model = Backbone.Model.extend({
    installUrl: '/admin/jolokia/exec/org.apache.karaf:type=feature,name=root/installFeature(java.lang.String,boolean)/',
    uninstallUrl: '/admin/jolokia/exec/org.apache.karaf:type=feature,name=root/uninstallFeature(java.lang.String,boolean)/',
    shutdownUrl: '/admin/jolokia/exec/org.apache.karaf:type=system,name=root/halt()',
    propertiesUrl: '/admin/jolokia/exec/org.apache.karaf:type=system,name=root/setProperty(java.lang.String,java.lang.String,boolean)/karaf.restart.jvm/true/false',
    restartUrl: '/admin/jolokia/exec/org.apache.karaf:type=system,name=root/reboot()',
    restartWrapperUrl: '/admin/jolokia/exec/org.tanukisoftware.wrapper:type=WrapperManager/restart()',
    defaults: function () {
      return {
        hasNext: true,
        hasPrevious: false,
        totalSteps: 4,
        stepNumber: 0,
        percentComplete: 0,
        busy: false,
        message: '',
        steps: [],
        showInstallProfileStep: false,
        selectedProfile: null,
        isCustomProfile: false
      };
    },
    initialize: function () {
      _.bindAll.apply(_, [this].concat(_.functions(this)));
      this.on('block', this.block);
      this.on('unblock', this.unblock);
    },
    setTotalSteps: function (numOfSteps) {
      var changeObj = {};
      changeObj.steps = [];
      for (var i = 0; i < numOfSteps; i++) {
        changeObj.steps.push({percentComplete: 0});
      }
      changeObj.totalSteps = numOfSteps;
      this.set(changeObj);
    },
    nextStep: function (message, percentComplete) {
      var stepNumber = this.get('stepNumber'),
          totalSteps = this.get('totalSteps'),
          changeObj = {};

      if (stepNumber < totalSteps) {
        if (!_.isUndefined(message)) {
          changeObj.message = message;
        }

        changeObj.steps = this.get('steps');
        if (!_.isUndefined(percentComplete)) {
          changeObj.steps[stepNumber].percentComplete = percentComplete;
        } else {
          changeObj.steps[stepNumber].percentComplete = 100;
        }

        changeObj.percentComplete = 0;
        _.each(changeObj.steps, function (step) {
          changeObj.percentComplete += step.percentComplete / totalSteps;
        });

        changeObj.percentComplete = Math.round(changeObj.percentComplete);

        if (changeObj.percentComplete > 100) {
          changeObj.percentComplete = 100;
        }

        if (changeObj.steps[stepNumber].percentComplete === 100) {
          _.extend(changeObj, _step.call(this, 1));
        }

        this.set(changeObj);
      }
    },
    block: function () {
      this.set({busy: true});
    },
    unblock: function () {
      this.set({busy: false});
    },
    previousStep: function () {
      this.set(_step.call(this, -1));
    },
    save: function (restart) {
      var that = this;
      wreqr.vent.trigger('modulePoller:stop');
      return $.ajax({
        type: 'GET',
        url: that.uninstallUrl + 'admin-modules-installer/true',
        dataType: 'JSON'
      }).then(function () {
        return $.ajax({
          type: 'GET',
          url: that.installUrl + 'admin-post-install-modules/true',
          dataType: 'JSON'
        }).then(function () {
          if (restart) {
            $.ajax({
              type: 'GET',
              url: that.propertiesUrl,
              dataType: 'JSON'
            }).done(function () {
              // try to restart the service wrapper. this will fail with 404 if
              // the wrapper is not installed
              $.ajax({
                type: 'GET',
                url: that.restartWrapperUrl,
                dataType: 'JSON'
              }).fail(function () {
                // Service wrapper is not installed, must be started via Karaf's script
                $.ajax({
                  type: 'GET',
                  url: that.restartUrl,
                  dataType: 'JSON'
                }).done(function () {
                  window.setTimeout(function () {
                    window.location.href = that.get("redirectUrl");
                  }, 60000);
                });
              }).done(function (data) {
                if (data.status === 2000) {
                  window.setTimeout(function () {
                    window.location.href = that.get("redirectUrl");
                  }, 60000);
                } else if (data.status === 404) {
                  // Service wrapper is not installed, must be started via Karaf's script
                  $.ajax({
                    type: 'GET',
                    url: that.restartUrl,
                    dataType: 'JSON'
                  }).done(function () {
                    window.setTimeout(function () {
                      window.location.href = that.get("redirectUrl");
                    }, 60000);
                  });
                }
              });
            });
          } else {
            $.ajax({
              type: 'GET',
              url: that.shutdownUrl,
              dataType: 'JSON'
            }).done(function () {
              window.setTimeout(function () {
                window.location.href = that.get("redirectUrl");
              }, 30000);
            });
          }
        });
      });
    }
  });

  return Installer;

});