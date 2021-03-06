import {Component, OnInit, Input} from '@angular/core';
import * as _ from "lodash";
import {Globalvar} from "../../constants/globalvar";
declare var DCM4CHE: any;

@Component({
  selector: 'iod-form-generator',
  templateUrl: './iod-form-generator.component.html',
  styles: []
})
export class IodFormGeneratorComponent implements OnInit {
    @Input() object;
    @Input() prefix;
    objectIsArray;

    constructor() { }
    privateCreator(tag) {
        if ("02468ACE".indexOf(tag.charAt(3)) < 0) {
            let block = tag.slice(4, 6);
            if (block !== "00") {
                let el = this.object[tag.slice(0, 4) + "00" + block];
                return el && el.Value && el.Value[0];
            }
        }
        return undefined;
    }

    ngOnInit() {
        console.log("attr=",this.object);
        console.log("oninit object",this.object);
        if(_.isArray(this.object)){
            this.objectIsArray = true;
        }else{
            this.objectIsArray = false;
        }
    }
    getKeys(obj){
        if(_.isArray(obj)){
            return obj;
        }else{
            return Object.keys(obj);
        }
    }
    options = Globalvar.OPTIONS;
    DCM4CHE = DCM4CHE;
    onChange(newValue, model) {
        _.set(this, model,newValue);
    }
    removeAttr(attrcode){
        console.log("attrcode",attrcode);
        switch(arguments.length) {
            case 2:
                if(this.object[arguments[0]].Value.length === 1){
                    delete  this.object[arguments[0]];
                }else{
                    this.object[arguments[0]].Value.splice(arguments[1], 1);
                }
                break;
            default:
                delete  this.object[arguments[0]];
                break;
        }
    };
}
