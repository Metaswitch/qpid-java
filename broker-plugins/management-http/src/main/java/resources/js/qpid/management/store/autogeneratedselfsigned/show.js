/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

define(["qpid/common/util",
        "dojo/query",
        "dijit/registry",
        "dojo/_base/connect",
        "dojo/_base/event",
        "dojo/domReady!"],
  function (util, query, registry, connect, event)
  {

    function AutoGeneratedSelfSignedKeyStoreProvider(data)
    {
        this.fields = [];
        this.management = data.parent.management;
        this.modelObj = data.parent.modelObj;
        var attributes = data.parent.management.metadata.getMetaData("KeyStore", "AutoGeneratedSelfSigned").attributes;
        for(var name in attributes)
        {
            this.fields.push(name);
        }
        var that = this;

        util.buildUI(data.containerNode, data.parent, "store/autogeneratedselfsigned/show.html", this.fields, this, function() {
            var getCertificateButton = query(".getCertificateButton", data.containerNode)[0];
            var getCertificateWidget = registry.byNode(getCertificateButton);
            connect.connect(getCertificateWidget, "onClick",
                function (evt) {
                    event.stop(evt);
                    that.getCertificate();
                });
            var getClientTrustStoreButton = query(".getClientTrustStoreButton", data.containerNode)[0];
            var getClientTrustStoreWidget = registry.byNode(getClientTrustStoreButton);
            connect.connect(getClientTrustStoreWidget, "onClick",
                function (evt) {
                    event.stop(evt);
                    that.getClientTrustStore();
                });
        });
    }

    AutoGeneratedSelfSignedKeyStoreProvider.prototype.update = function(data)
                                                                {
                                                                    util.updateUI(data, this.fields, this);
                                                                };

    AutoGeneratedSelfSignedKeyStoreProvider.prototype.getCertificate = function()
    {
        var modelObj = this.modelObj;
        this.management.download({ parent: modelObj, name: "getCertificate", type: modelObj.type})
    };


    AutoGeneratedSelfSignedKeyStoreProvider.prototype.getClientTrustStore = function()
    {
        var modelObj = this.modelObj;
        this.management.download({ parent: modelObj, name: "getClientTrustStore", type: modelObj.type})
    };


      return AutoGeneratedSelfSignedKeyStoreProvider;
  }
);
