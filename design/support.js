// Kotomo's renderer for the design artboards (design/*.dc.html), written for this repository so the
// drafts open without the design tool. It implements the subset of the canvas template format the
// artboards use:
//   <x-dc> … </x-dc>        the artboard's template; <helmet> children move into <head>
//   {{ expression }}         in text and attribute values, evaluated against the component's values
//   <sc-for list="{{xs}}" as="x">   repeats its children for every item
//   <sc-if value="{{cond}}">        keeps its children when the value is truthy
//   <dc-import name="Keyboard" w="{{ 412 }}" key-h="{{ 52 }}">   renders Keyboard.dc.html with
//                            those props (kebab-case names become camelCase; hint-* is ignored)
//   onClick / onChange="{{fn}}"     event handlers; checked="{{b}}" sets the property
//   <script type="text/x-dc" data-dc-script data-props='{"name":{"default":…}}'>
//     class Component extends DCLogic { renderVals() { return {…}; } }   (this.props, this.state,
//     this.setState() re-renders)
// Imported components are fetched, so serve the folder over HTTP (see README.md); file:// pages
// render everything except imported components.
(() => {
  'use strict';

  const style = document.createElement('style');
  style.textContent = 'x-dc{display:none}';
  document.head.appendChild(style);

  /** Base class the artboards' Component classes extend. */
  class DCLogic {
    constructor(props) {
      this.props = props || {};
      this.state = {};
      this.rerender = () => {};
    }
    setState(patch) {
      this.state = Object.assign({}, this.state, patch);
      this.rerender();
    }
    renderVals() {
      return {};
    }
  }

  const ONE = /^\s*\{\{([\s\S]*?)\}\}\s*$/;
  const ANY = /\{\{([\s\S]*?)\}\}/g;
  const evaluators = new Map();

  /** Evaluates one template expression against a scope object (missing names read as undefined). */
  function evaluate(expr, scope) {
    let fn = evaluators.get(expr);
    if (!fn) {
      // eslint-disable-next-line no-new-func
      fn = new Function('scope', 'with (scope) { return (' + expr + '); }');
      evaluators.set(expr, fn);
    }
    const guarded = new Proxy(scope, {
      has: () => true,
      get: (t, k) => (k === Symbol.unscopables ? undefined : k in t ? t[k] : globalThis[k]),
    });
    try {
      return fn(guarded);
    } catch (e) {
      console.warn('design: cannot evaluate {{' + expr + '}}', e);
      return undefined;
    }
  }

  /** Returns an attribute's value: the expression's own value when it is a single {{…}}. */
  function value(raw, scope) {
    const m = ONE.exec(raw);
    return m ? evaluate(m[1], scope) : interpolate(raw, scope);
  }

  /** Replaces every {{…}} in a string. */
  function interpolate(raw, scope) {
    return raw.replace(ANY, (_, e) => {
      const v = evaluate(e, scope);
      return v === undefined || v === null ? '' : String(v);
    });
  }

  const camel = (s) => s.replace(/-([a-z])/g, (_, c) => c.toUpperCase());
  const components = new Map();

  /** Loads Name.dc.html once: its template and its Component class. */
  function load(name) {
    if (!components.has(name)) {
      components.set(name, fetch(name + '.dc.html').then((r) => {
        if (!r.ok) throw new Error(name + '.dc.html: HTTP ' + r.status);
        return r.text();
      }).then((text) => parse(new DOMParser().parseFromString(text, 'text/html'))));
    }
    return components.get(name);
  }

  /** Reads an artboard document: its template source, helmet nodes, Component class and prop defaults. */
  function parse(doc) {
    const xdc = doc.querySelector('x-dc');
    const script = doc.querySelector('script[data-dc-script]');
    let Component = DCLogic;
    const defaults = {};
    if (script) {
      // eslint-disable-next-line no-new-func
      Component = new Function('DCLogic', script.textContent + '\nreturn Component;')(DCLogic);
      try {
        const props = JSON.parse(script.getAttribute('data-props') || '{}');
        for (const [k, v] of Object.entries(props)) {
          if (!k.startsWith('$') && v && typeof v === 'object' && 'default' in v) defaults[k] = v.default;
        }
      } catch (e) {
        console.warn('design: bad data-props', e);
      }
    }
    const helmet = xdc ? xdc.querySelector('helmet') : null;
    if (helmet) helmet.remove();
    return { source: xdc ? xdc.innerHTML : '', helmet, Component, defaults };
  }

  const adopted = new Set();

  /** Moves a component's <helmet> nodes (fonts, styles) into this document's head, once each. */
  function adoptHelmet(helmet) {
    if (!helmet) return;
    for (const node of Array.from(helmet.children)) {
      const key = node.outerHTML;
      if (adopted.has(key)) continue;
      adopted.add(key);
      document.head.appendChild(document.importNode(node, true));
    }
  }

  /** Mounts a parsed component into `host` with `props`; setState re-renders in place. */
  function mount(host, parsed, props) {
    adoptHelmet(parsed.helmet);
    const instance = new parsed.Component(Object.assign({}, parsed.defaults, props));
    const template = document.createElement('template');
    template.innerHTML = parsed.source;
    const draw = () => {
      const vals = instance.renderVals() || {};
      const scope = Object.assign({}, instance.props, vals);
      host.replaceChildren(...render(template.content, scope));
    };
    instance.rerender = draw;
    draw();
  }

  /** Renders the children of `parent` with `scope` and returns the new nodes. */
  function render(parent, scope) {
    const out = [];
    for (const node of Array.from(parent.childNodes)) {
      if (node.nodeType === Node.TEXT_NODE) {
        out.push(document.createTextNode(node.data.includes('{{') ? interpolate(node.data, scope) : node.data));
        continue;
      }
      if (node.nodeType !== Node.ELEMENT_NODE) continue;
      const tag = node.localName;
      if (tag === 'helmet') continue;
      if (tag === 'sc-for') {
        const list = value(node.getAttribute('list') || '', scope) || [];
        const as = node.getAttribute('as') || 'item';
        Array.from(list).forEach((item, index) => {
          out.push(...render(node, Object.assign({}, scope, { [as]: item, index })));
        });
        continue;
      }
      if (tag === 'sc-if') {
        if (value(node.getAttribute('value') || '', scope)) out.push(...render(node, scope));
        continue;
      }
      if (tag === 'dc-import') {
        const props = {};
        for (const a of Array.from(node.attributes)) {
          if (a.name === 'name' || a.name.startsWith('hint-')) continue;
          props[camel(a.name)] = value(a.value, scope);
        }
        const host = document.createElement('div');
        host.style.display = 'contents';
        load(node.getAttribute('name')).then((parsed) => mount(host, parsed, props)).catch((e) => {
          host.textContent = '[' + node.getAttribute('name') + ': ' + e.message + ']';
          console.warn('design:', e);
        });
        out.push(host);
        continue;
      }
      const el = document.createElement(tag);
      for (const a of Array.from(node.attributes)) {
        if (!a.value.includes('{{')) {
          el.setAttribute(a.name, a.value);
          continue;
        }
        const v = value(a.value, scope);
        if (a.name.startsWith('on')) {
          if (typeof v === 'function') el.addEventListener(a.name.slice(2), () => v());
        } else if (a.name === 'checked') {
          el.checked = !!v;
        } else if (v !== false && v !== undefined && v !== null) {
          el.setAttribute(a.name, String(v));
        }
      }
      el.append(...render(node, scope));
      out.push(el);
    }
    return out;
  }

  window.addEventListener('DOMContentLoaded', () => {
    const page = parse(document);
    for (const old of document.querySelectorAll('x-dc')) old.remove();
    const host = document.createElement('div');
    document.body.prepend(host);
    mount(host, page, {});
  });
})();
