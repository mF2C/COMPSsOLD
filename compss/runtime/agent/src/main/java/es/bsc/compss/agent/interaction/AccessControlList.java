/*         
 *  Copyright 2002-2018 Barcelona Supercomputing Center (www.bsc.es)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package es.bsc.compss.agent.interaction;


public class AccessControlList {

    Owner owner;
    Rule[] rules = new Rule[0];

    public AccessControlList() {
    }

    public Owner getOwner() {
        return owner;
    }

    public void setOwner(Owner owner) {
        this.owner = owner;
    }

    public Rule[] getRules() {
        return rules;
    }

    public void setRules(Rule[] rules) {
        this.rules = rules;
    }

    public void addRule(Rule rule) {
        int oldRulesCount = this.rules.length;
        Rule[] newRules = new Rule[oldRulesCount + 1];
        System.arraycopy(this.rules, 0, newRules, 0, oldRulesCount);
        newRules[oldRulesCount] = rule;
        this.rules = newRules;
    }

}
