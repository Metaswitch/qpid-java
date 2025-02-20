/*
 *
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
 *
 */
define(["dojo/_base/lang",
        "dojo/dom",
        "dojo/dom-construct",
        "dijit/registry",
        "dojo/parser",
        "dojo/store/Memory",
        "dojo/_base/array",
        "dojo/_base/event",
        'dojo/json',
        "qpid/common/util",
        "dojo/text!addStore.html",
        "dojo/store/Memory",
        "dojox/validate/us",
        "dojox/validate/web",
        "dijit/Dialog",
        "dijit/form/CheckBox",
        "dijit/form/Textarea",
        "dijit/form/ComboBox",
        "dijit/form/TextBox",
        "dijit/form/ValidationTextBox",
        "dijit/form/Button",
        "dijit/form/Form",
        "dijit/layout/ContentPane",
        "dojox/layout/TableContainer",
        "dojo/domReady!"],
    function (lang, dom, construct, registry, parser, memory, array, event, json, util, template)
    {
        var addStore =
        {
            init: function()
            {
                var that=this;
                this.containerNode = construct.create("div", {innerHTML: template});
                parser.parse(this.containerNode).then(function(instances) { that._postParse(); });
            },
            _postParse: function()
            {
                var that=this;
                this.storeName = registry.byId("addStore.name");
                this.storeName.set("regExpGen", util.nameOrContextVarRegexp);

                this.dialog = registry.byId("addStore");
                this.addButton = registry.byId("addStore.addButton");
                this.cancelButton = registry.byId("addStore.cancelButton");
                this.cancelButton.on("click", function(e){that._cancel(e);});
                this.addButton.on("click", function(e){that._add(e);});

                this.storeTypeFieldsContainer = dom.byId("addStore.typeFields");
                this.storeForm = registry.byId("addStore.form");

                this.storeType = registry.byId("addStore.type");
                this.storeType.on("change", function(type){that._storeTypeChanged(type);});
            },
            setupTypeStore: function(management, category, modelObj)
            {
                this.management = management;
                this.modelObj = modelObj;
                var metadata = management.metadata;
                this.category = category;
                var storeTypeSupportedTypes = metadata.getTypesForCategory(category);
                storeTypeSupportedTypes.sort();
                var storeTypeStore = util.makeTypeStore(storeTypeSupportedTypes);
                this.storeType.set("store", storeTypeStore);
            },
            show: function(effectiveData)
            {
                this.effectiveData = effectiveData;
                this._destroyTypeFields(this.containerNode);
                this.storeForm.reset();

                if (effectiveData)
                {
                    this._initFields(effectiveData);
                }
                this.storeName.set("disabled", effectiveData == null ? false : true);
                this.storeType.set("disabled", effectiveData == null ? false : true);
                this.dialog.set("title", effectiveData == null ? "Add Key Store" : "Edit Key Store - " + effectiveData.name)
                this.dialog.show();
            },
            _initFields:function(data)
            {
                var type = data["type"];
                var metadata = this.management.metadata;
                var attributes = metadata.getMetaData(this.category, type).attributes;
                for(var name in attributes)
                {
                    var widget = registry.byId("addStore."+name);
                    if (widget)
                    {
                        widget.set("value", data[name]);
                    }
                }
            },
            _cancel: function(e)
            {
                event.stop(e);
                this._destroyTypeFields(this.storeTypeFieldsContainer);
                this.dialog.hide();
            },
            _add: function(e)
            {
                event.stop(e);
                this._submit();
            },
            _submit: function()
            {
                if (this.storeForm.validate())
                {
                    var that = this;

                    function disableButtons(disabled) {
                        that.addButton.set("disabled", disabled);
                        that.cancelButton.set("disabled", disabled);
                    }

                    disableButtons(true);

                    var storeData = util.getFormWidgetValues(this.storeForm, this.initialData);

                    if (this.effectiveData)
                    {
                        // update request
                        this.management.update(this.modelObj, storeData).then(function(x){ disableButtons(false); that.dialog.hide();}, function(err) { disableButtons(false); that.management.errorHandler(err); });
                    }
                    else
                    {
                        this.management.create(this.category, this.modelObj, storeData).then(function(x){ disableButtons(false); that.dialog.hide();}, function(err) { disableButtons(false); that.management.errorHandler(err); });
                    }
                }
                else
                {
                    alert('Form contains invalid data. Please correct first');
                }
            },
            _storeTypeChanged: function(type)
            {
                this._typeChanged(type, this.storeTypeFieldsContainer, "qpid/management/store/", this.category );
            },
            _destroyTypeFields: function(typeFieldsContainer)
            {
                var widgets = registry.findWidgets(typeFieldsContainer);
                array.forEach(widgets, function(item) { item.destroyRecursive();});
                construct.empty(typeFieldsContainer);
            },
            _typeChanged: function(type, typeFieldsContainer, baseUrl, category )
            {
                 this._destroyTypeFields(typeFieldsContainer);

                 if (type)
                 {
                     var that = this;
                     require([ baseUrl + type.toLowerCase() + "/add"], function(typeUI)
                     {
                         try
                         {
                             var metadata = that.management.metadata;
                             typeUI.show({containerNode:typeFieldsContainer, parent: that, data: that.initialData, effectiveData: that.effectiveData, metadata: metadata});
                             util.applyMetadataToWidgets(typeFieldsContainer, category, type, metadata);
                         }
                         catch(e)
                         {
                             console.warn(e);
                         }
                     });
                 }
            }
        };

        try
        {
            addStore.init();
        }
        catch(e)
        {
            console.warn(e);
        }
        return addStore;
    });
