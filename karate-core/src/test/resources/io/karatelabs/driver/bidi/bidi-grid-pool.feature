@bidi @pool
Feature: BiDi driver pooling on Selenium Grid

  Background:
    * def gridUrl = karate.properties['karate.bidi.gridUrl']
    * def browserName = karate.properties['karate.bidi.browserName']
    * def serverUrl = karate.properties['karate.bidi.serverUrl']
    * configure driver = { type: 'bidi', browserName: '#(browserName)', webDriverUrl: '#(gridUrl)', timeout: 15000 }

  Scenario Outline: pooled session completes a visible application form for <name>
    * driver serverUrl + '/input'
    * match text('h1') == 'Input Test Page'
    * input('#username', '<name>')
    * input('#email', '<email>')
    * select('#country', '<country>')
    * click('#submit-btn')
    * waitForText('#form-output', '<name>')
    * match text('#form-output') contains '<email>'
    * delay(350)
    * screenshot()

    Examples:
      | name   | email               | country |
      | Ada    | ada@example.test    | us      |
      | Grace  | grace@example.test  | uk      |
      | Linus  | linus@example.test  | ca      |
      | James  | james@example.test  | au      |
