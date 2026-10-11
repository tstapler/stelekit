/*
 * Vendored from aresstack/mermaid-java (https://github.com/aresstack/mermaid-java),
 * commit as of 2026-09-15, file src/main/resources/mermaid/browser-shim.js.
 * MIT License — Copyright (c) 2026 aresstack.
 * See project_plans/mermaid-diagrams/decisions/ADR-001-mermaid-rendering-strategy.md
 * (Open Items) for why this shim was chosen over a from-scratch minimal getBBox stub:
 * mermaid.js's real layout/DOMPurify pass needs a materially complete headless DOM
 * (createNodeIterator, NodeFilter, document.implementation.createHTMLDocument, etc.),
 * not just getBBox — this file supplies that surface.
 */
/*
 * browser-shim.js — Minimal browser-like environment for running Mermaid inside GraalJS.
 *
 * This shim provides enough of the window/document/navigator API surface
 * so that mermaid.min.js (IIFE build, v11.x) can load and initialize.
 * It is NOT a general-purpose browser polyfill.
 */

// ── Event target mixin ──────────────────────────────────────────────────────
function EventTargetMixin(obj) {
    obj._listeners = {};
    obj.addEventListener = function(type, fn) {
        if (!obj._listeners[type]) obj._listeners[type] = [];
        obj._listeners[type].push(fn);
    };
    obj.removeEventListener = function(type, fn) {
        if (!obj._listeners[type]) return;
        var idx = obj._listeners[type].indexOf(fn);
        if (idx >= 0) obj._listeners[type].splice(idx, 1);
    };
    obj.dispatchEvent = function(evt) {
        var type = evt.type || evt;
        var fns = obj._listeners[type] || [];
        for (var i = 0; i < fns.length; i++) {
            try { fns[i](evt); } catch(e) { /* ignore */ }
        }
    };
    return obj;
}

// ── Minimal selector engine (supports #id, .class, tagName, and simple combos) ─
function _matchesSelector(el, sel) {
    if (!el || el.nodeType !== 1) return false;
    sel = sel.trim();
    // Handle comma-separated selectors (OR)
    if (sel.indexOf(',') >= 0) {
        var parts = sel.split(',');
        for (var i = 0; i < parts.length; i++) {
            if (_matchesSelector(el, parts[i])) return true;
        }
        return false;
    }
    // Trim descendant parts - only match the last segment for .matches()
    var segments = sel.split(/\s+/);
    var last = segments[segments.length - 1];
    // Simple selectors
    if (last === '*') return true;

    // :not() pseudo-selector — negate the inner selector.
    // Supports compound forms like "tag:not(sel)" (e.g. "g:not(:first-child)").
    var notMatch = last.match(/^([^:]*):not\(([^)]+)\)(.*)$/);
    if (notMatch) {
        var beforeNot = notMatch[1]; // tag/class before :not (e.g. "g")
        var innerSel = notMatch[2];  // selector inside :not() (e.g. ":first-child")
        var afterNot = notMatch[3];  // anything after :not()
        // Element must match the part before :not (if any)
        if (beforeNot && !_matchesSelector(el, beforeNot)) return false;
        // Element must NOT match the inner selector
        if (_matchesSelector(el, innerSel)) return false;
        // Element must match any remaining part after :not
        if (afterNot && !_matchesSelector(el, afterNot.charAt(0) === ':' ? '*' + afterNot : afterNot)) return false;
        return true;
    }

    // :first-child pseudo-selector — used by D3's insert(tag, ":first-child")
    // to insert shape elements BEFORE labels in node groups
    if (last === ':first-child') {
        if (!el.parentNode || !el.parentNode.childNodes) return false;
        for (var fc = 0; fc < el.parentNode.childNodes.length; fc++) {
            if (el.parentNode.childNodes[fc].nodeType === 1) return el.parentNode.childNodes[fc] === el;
        }
        return false;
    }
    // :last-child pseudo-selector
    if (last === ':last-child') {
        if (!el.parentNode || !el.parentNode.childNodes) return false;
        for (var lc = el.parentNode.childNodes.length - 1; lc >= 0; lc--) {
            if (el.parentNode.childNodes[lc].nodeType === 1) return el.parentNode.childNodes[lc] === el;
        }
        return false;
    }
    if (last.charAt(0) === '#') return el.id === last.substring(1);
    if (last.charAt(0) === '.') return (' ' + (el.className || '') + ' ').indexOf(' ' + last.substring(1) + ' ') >= 0;
    // [attr="value"] selector
    var attrMatch = last.match(/^\[([a-zA-Z_-]+)(?:([~|^$*]?)=["']([^"']*)["'])?\]$/);
    if (attrMatch) {
        var attrName = attrMatch[1];
        var attrOp = attrMatch[2] || '';
        var attrVal = attrMatch[3];
        var actual = attrName === 'id' ? el.id : (attrName === 'class' ? el.className : (el._attrs ? el._attrs[attrName] : el.getAttribute ? el.getAttribute(attrName) : null));
        if (attrVal === undefined) return actual !== undefined && actual !== null; // [attr] = has attribute
        if (attrOp === '') return actual === attrVal; // [attr=val]
        if (attrOp === '~') return actual && (' ' + actual + ' ').indexOf(' ' + attrVal + ' ') >= 0; // [attr~=val]
        if (attrOp === '|') return actual && (actual === attrVal || actual.indexOf(attrVal + '-') === 0); // [attr|=val]
        if (attrOp === '^') return actual && actual.indexOf(attrVal) === 0; // [attr^=val]
        if (attrOp === '$') return actual && actual.indexOf(attrVal, actual.length - attrVal.length) >= 0; // [attr$=val]
        if (attrOp === '*') return actual && actual.indexOf(attrVal) >= 0; // [attr*=val]
        return false;
    }
    // Tag name (no special chars)
    if (last.indexOf('#') < 0 && last.indexOf('.') < 0 && last.indexOf('[') < 0) {
        return (el.tagName || '').toLowerCase() === last.toLowerCase();
    }
    // tag#id
    var m = last.match(/^([a-zA-Z][a-zA-Z0-9-]*)#([^\.\[]+)/);
    if (m) return (el.tagName || '').toLowerCase() === m[1].toLowerCase() && el.id === m[2];
    // tag.class
    m = last.match(/^([a-zA-Z][a-zA-Z0-9-]*)\.([^\.\[#]+)/);
    if (m) return (el.tagName || '').toLowerCase() === m[1].toLowerCase() && (' ' + (el.className || '') + ' ').indexOf(' ' + m[2] + ' ') >= 0;
    // tag[attr="value"]
    m = last.match(/^([a-zA-Z][a-zA-Z0-9-]*)\[/);
    if (m) {
        var tagPart = m[1];
        var attrPart = last.substring(tagPart.length);
        return (el.tagName || '').toLowerCase() === tagPart.toLowerCase() && _matchesSelector(el, attrPart);
    }
    return false;
}

function _querySelectorAll(root, sel) {
    var results = [];
    function walk(node) {
        if (!node) return;
        var children = node.childNodes || [];
        for (var i = 0; i < children.length; i++) {
            var child = children[i];
            if (child.nodeType === 1 && _matchesSelector(child, sel)) {
                results.push(child);
            }
            walk(child);
        }
    }
    walk(root);
    return results;
}

function _querySelector(root, sel) {
    var all = _querySelectorAll(root, sel);
    return all.length > 0 ? all[0] : null;
}

// ── CSSStyleDeclaration stub ────────────────────────────────────────────────
function createStyleObject() {
    var style = {};
    style._props = {};
    style.setProperty = function(name, value, priority) {
        style._props[name] = value;
        // Also set camelCase version for direct property access
        var camel = name.replace(/-([a-z])/g, function(m, c) { return c.toUpperCase(); });
        style[camel] = value;
    };
    style.getPropertyValue = function(name) {
        return style._props[name] || '';
    };
    style.removeProperty = function(name) {
        var old = style._props[name] || '';
        delete style._props[name];
        var camel = name.replace(/-([a-z])/g, function(m, c) { return c.toUpperCase(); });
        delete style[camel];
        return old;
    };
    style.getPropertyPriority = function(name) { return ''; };
    style.cssText = '';
    style.length = 0;
    style.item = function(index) { return ''; };
    return style;
}

// ── SVG namespace constants ──────────────────────────────────────────────────
var SVG_NS = 'http://www.w3.org/2000/svg';
var XHTML_NS = 'http://www.w3.org/1999/xhtml';
var XLINK_NS = 'http://www.w3.org/1999/xlink';

// ── XML attribute escaping ──────────────────────────────────────────────────
function _escapeXmlAttr(val) {
    if (val == null) return '';
    return String(val).replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}
function _escapeXmlText(val) {
    if (val == null) return '';
    return String(val).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

// ── Element serialization helper ────────────────────────────────────────────
// SVG element names that must keep camelCase (SVG is case-sensitive unlike HTML)
var SVG_CAMEL_CASE_TAGS = {
    'LINEARGRADIENT': 'linearGradient',
    'RADIALGRADIENT': 'radialGradient',
    'CLIPPATH': 'clipPath',
    'TEXTPATH': 'textPath',
    'FOREIGNOBJECT': 'foreignObject',
    'ANIMATETRANSFORM': 'animateTransform',
    'ANIMATEMOTION': 'animateMotion',
    'FEGAUSSIANBLUR': 'feGaussianBlur',
    'FECOLORMATRIX': 'feColorMatrix',
    'FECOMPONENTTRANSFER': 'feComponentTransfer',
    'FECOMPOSITE': 'feComposite',
    'FECONVOLVEMATRIX': 'feConvolveMatrix',
    'FEDIFFUSELIGHTING': 'feDiffuseLighting',
    'FEDISPLACEMENTMAP': 'feDisplacementMap',
    'FEDISTANTLIGHT': 'feDistantLight',
    'FEDROPSHADOW': 'feDropShadow',
    'FEFLOOD': 'feFlood',
    'FEFUNCA': 'feFuncA',
    'FEFUNCB': 'feFuncB',
    'FEFUNCG': 'feFuncG',
    'FEFUNCR': 'feFuncR',
    'FEIMAGE': 'feImage',
    'FEMERGE': 'feMerge',
    'FEMERGENODE': 'feMergeNode',
    'FEMORPHOLOGY': 'feMorphology',
    'FEOFFSET': 'feOffset',
    'FEPOINTLIGHT': 'fePointLight',
    'FESPECULARLIGHTING': 'feSpecularLighting',
    'FESPOTLIGHT': 'feSpotLight',
    'FETILE': 'feTile',
    'FETURBULENCE': 'feTurbulence',
    'GLYPHREF': 'glyphRef',
    'ALTGLYPH': 'altGlyph',
    'ALTGLYPHDEF': 'altGlyphDef',
    'ALTGLYPHITEM': 'altGlyphItem',
    'FEBLEND': 'feBlend'
};

function _svgTagName(upper) {
    return SVG_CAMEL_CASE_TAGS[upper] || upper.toLowerCase();
}

function _serializeNode(node, parentNs) {
    if (!node) return '';
    if (node.nodeType === 3) return _escapeXmlText(node.textContent || '');
    if (node.nodeType !== 1) return '';

    var rawTag = node.tagName || 'div';
    var ns = node.namespaceURI || XHTML_NS;
    // SVG elements are case-sensitive; HTML elements are lowercased
    var tag = (ns === SVG_NS) ? _svgTagName(rawTag) : rawTag.toLowerCase();
    var attrs = '';

    // Emit xmlns only when namespace differs from parent
    if (ns && ns !== parentNs) {
        attrs += ' xmlns="' + ns + '"';
    }

    if (node._attrs) {
        var keys = Object.keys(node._attrs);
        for (var i = 0; i < keys.length; i++) {
            var key = keys[i];
            var val = node._attrs[key];
            // Don't double-emit xmlns if we already added it above
            if (key === 'xmlns' && val === ns && ns !== parentNs) continue;
            // Skip alignment-baseline — Batik rejects "central" value
            if (key === 'alignment-baseline') continue;
            // Skip function values (D3/Mermaid may store callbacks in attrs)
            if (typeof val === 'function') continue;
            attrs += ' ' + key + '="' + _escapeXmlAttr(val) + '"';
        }
    }

    // Serialize style properties inline
    if (node.style && node.style._props) {
        var styleKeys = Object.keys(node.style._props);
        if (styleKeys.length > 0) {
            var styleStr = '';
            for (var s = 0; s < styleKeys.length; s++) {
                var sval = node.style._props[styleKeys[s]];
                // Skip function values (polyfill methods, callbacks)
                if (typeof sval === 'function') continue;
                if (styleStr) styleStr += '; ';
                styleStr += styleKeys[s] + ': ' + sval;
            }
            if (styleStr && (!node._attrs || !node._attrs['style'])) {
                attrs += ' style="' + _escapeXmlAttr(styleStr) + '"';
            }
        }
    }

    // Serialize children
    var inner = '';
    if (node.childNodes && node.childNodes.length > 0) {
        for (var j = 0; j < node.childNodes.length; j++) {
            inner += _serializeNode(node.childNodes[j], ns);
        }
    } else if (node._innerHTMLRaw) {
        inner = node._innerHTMLRaw;
    }

    // For <style> elements, wrap content in CDATA so CSS selectors with >
    // don't break XML parsing in downstream Java (Batik, JAXP)
    if (tag === 'style' && inner && (inner.indexOf('>') >= 0 || inner.indexOf('<') >= 0 || inner.indexOf('&') >= 0)) {
        if (inner.indexOf('<![CDATA[') < 0) {
            inner = '<![CDATA[' + inner + ']]>';
        }
    }

    // Self-closing void SVG elements
    var voidSvgTags = { 'path': 1, 'circle': 1, 'ellipse': 1, 'line': 1, 'polyline': 1,
                        'polygon': 1, 'rect': 1, 'use': 1, 'image': 1, 'br': 1, 'hr': 1,
                        'img': 1, 'input': 1, 'meta': 1, 'link': 1 };
    if (!inner && voidSvgTags[tag]) {
        return '<' + tag + attrs + '/>';
    }

    return '<' + tag + attrs + '>' + inner + '</' + tag + '>';
}

// ── Text width estimation (for layout without a real rendering engine) ──────
function _sanitizeTextForMeasurement(text) {
    if (!text) return '';
    // Sanity check: if the "text" is actually CSS or huge markup content, cap it
    if (text.length > 200 || text.indexOf('{') >= 0 || text.indexOf('}') >= 0) {
        var stripped = text.replace(/[^a-zA-Z0-9äöüÄÖÜß\s.,!?:;\-()]/g, '').trim();
        if (stripped.length === 0) return '';
        if (stripped.length > 50) stripped = stripped.substring(0, 50);
        return stripped;
    }
    return text;
}

function _estimateTextWidth(el) {
    var text = _sanitizeTextForMeasurement(_collectAllText(el));
    if (!text) return 0;

    // Use Java bridge for accurate pixel-level text measurement if available
    if (typeof javaBridge !== 'undefined' && javaBridge.measureTextWidth) {
        var fontFamily = _resolveFontFamily(el) || '"trebuchet ms", verdana, arial, sans-serif';
        var fontSize = _resolveFontSize(el) || 16;
        try {
            return javaBridge.measureTextWidth(text, fontFamily, fontSize);
        } catch (e) {
            // Fall through to estimation
        }
    }

    // Fallback: rough estimation (~8px per character at 16px font)
    return text.length * 8 + 16;
}

/**
 * Measure text and return full metrics {width, ascent, descent, height}.
 * Uses Java bridge measureTextFull for pixel-accurate values.
 * Falls back to approximations if bridge is unavailable.
 */
function _measureTextMetrics(el) {
    var text = _sanitizeTextForMeasurement(_collectAllText(el));
    if (!text) return { width: 0, ascent: 0, descent: 0, height: 0 };

    var fontFamily = _resolveFontFamily(el) || '"trebuchet ms", verdana, arial, sans-serif';
    var fontSize = _resolveFontSize(el) || 16;

    // Use Java bridge for accurate measurement
    if (typeof javaBridge !== 'undefined' && javaBridge.measureTextFull) {
        try {
            var result = javaBridge.measureTextFull(text, fontFamily, fontSize);
            var parts = ('' + result).split(',');
            return {
                width:   parseFloat(parts[0]) || 0,
                ascent:  parseFloat(parts[1]) || Math.round(fontSize * 0.8),
                descent: parseFloat(parts[2]) || Math.round(fontSize * 0.2),
                height:  parseFloat(parts[3]) || Math.round(fontSize * 1.2)
            };
        } catch (e) {
            // Fall through to estimation
        }
    }

    // Fallback: width from _estimateTextWidth, height approximated
    var w = _estimateTextWidth(el);
    var asc = Math.round(fontSize * 0.8);
    var desc = Math.round(fontSize * 0.25);
    return { width: w, ascent: asc, descent: desc, height: asc + desc };
}

/**
 * Resolve the effective text-anchor for an SVG text/tspan element.
 * Checks inline style, style attribute, and dedicated attribute.
 * Returns 'start', 'middle', or 'end'. Default is 'start' per SVG spec.
 */
function _resolveTextAnchor(el) {
    var current = el;
    while (current && current.nodeType === 1) {
        // Check style object
        if (current.style && current.style.textAnchor) {
            return current.style.textAnchor;
        }
        if (current.style && current.style._props && current.style._props['text-anchor']) {
            return current.style._props['text-anchor'];
        }
        // Check style attribute string
        var styleAttr = current._attrs && current._attrs['style'];
        if (styleAttr) {
            var match = styleAttr.match(/text-anchor\s*:\s*(\w+)/);
            if (match) return match[1].trim();
        }
        // Check text-anchor attribute (SVG)
        var ta = current._attrs && current._attrs['text-anchor'];
        if (ta) return ta;
        current = current.parentNode;
    }
    return 'start';
}

/**
 * Resolve the effective font-family for an element by checking:
 * 1. inline style font-family
 * 2. class-based defaults (Mermaid uses specific classes)
 * 3. inherited from ancestors
 * 4. global default
 */
function _resolveFontFamily(el) {
    var current = el;
    while (current && current.nodeType === 1) {
        // Check style object
        if (current.style && current.style.fontFamily) {
            return current.style.fontFamily;
        }
        // Check style attribute string
        var styleAttr = current._attrs && current._attrs['style'];
        if (styleAttr) {
            var match = styleAttr.match(/font-family\s*:\s*([^;]+)/);
            if (match) return match[1].trim();
        }
        // Check font-family attribute (SVG text elements)
        var ff = current._attrs && current._attrs['font-family'];
        if (ff) return ff;
        // Check CSS font shorthand attribute (e.g. font="11px sans-serif")
        // Extract family part after the size component
        var fontShort2 = current._attrs && current._attrs['font'];
        if (fontShort2) {
            var fMatch = String(fontShort2).match(/\d+(?:\.\d+)?\s*px\s+(.+)/);
            if (fMatch) return fMatch[1].trim();
        }
        current = current.parentNode;
    }
    return null;
}

/**
 * Resolve the effective font-size for an element.
 * Handles em, px, pt units and inherits from ancestors.
 */
function _resolveFontSize(el) {
    var current = el;
    var parentFs = null; // lazy-resolved parent font-size for em conversion
    while (current && current.nodeType === 1) {
        var raw = null;
        // Check style object
        if (current.style && current.style.fontSize) {
            raw = current.style.fontSize;
        }
        // Check style attribute string
        if (!raw) {
            var styleAttr = current._attrs && current._attrs['style'];
            if (styleAttr) {
                var match = styleAttr.match(/font-size\s*:\s*([^;]+)/);
                if (match) raw = match[1].trim();
            }
        }
        // Check font-size attribute (SVG text elements)
        if (!raw) {
            var fs = current._attrs && current._attrs['font-size'];
            if (fs) raw = String(fs);
        }
        // Check CSS font shorthand attribute (e.g. font="11px sans-serif")
        // D3 sets this on temporary text elements for measurement.
        if (!raw) {
            var fontShort = current._attrs && current._attrs['font'];
            if (fontShort) {
                var fsMatch = String(fontShort).match(/(\d+(?:\.\d+)?)\s*px/);
                if (fsMatch) raw = fsMatch[1] + 'px';
            }
        }
        if (raw) {
            if (raw.indexOf('em') >= 0) {
                // em = relative to parent font-size
                parentFs = _resolveFontSize(current.parentNode) || 16;
                return parseFloat(raw) * parentFs;
            }
            if (raw.indexOf('%') >= 0) {
                parentFs = _resolveFontSize(current.parentNode) || 16;
                return parseFloat(raw) / 100 * parentFs;
            }
            // px, pt, or bare number
            var parsed = parseFloat(raw);
            if (!isNaN(parsed) && parsed > 0) return parsed;
        }
        current = current.parentNode;
    }
    return null;
}

/**
 * Recursively collect all text content from an element and its descendants.
 * This handles: direct text nodes, <tspan>, <span>, <div>, foreignObject, innerHTML.
 */
function _collectAllText(el) {
    if (!el) return '';
    // Text node
    if (el.nodeType === 3) return el.textContent || '';
    // Element node — collect from children recursively
    var text = '';
    if (el.childNodes && el.childNodes.length > 0) {
        for (var i = 0; i < el.childNodes.length; i++) {
            text += _collectAllText(el.childNodes[i]);
        }
    }
    // Fallback: _textContent property (set via textContent setter)
    if (!text && el._textContent) text = el._textContent;
    // Fallback: innerHTML raw — only use if it looks like a simple label, not full markup/CSS.
    // Mermaid sets innerHTML to complex structures like <style>...</style><g>...</g> which
    // after tag-stripping becomes a huge CSS string, producing wildly inflated measurements.
    if (!text && el._innerHTMLRaw) {
        var raw = el._innerHTMLRaw;
        // Skip if it contains <style>, <svg>, or lots of CSS-like content
        if (raw.indexOf('<style') < 0 && raw.indexOf('<svg') < 0 && raw.length < 500) {
            text = raw.replace(/<[^>]*>/g, '').trim();
        }
    }
    return text;
}

// ── Dimension computation for getBBox / getBoundingClientRect ────────────────

/**
 * Attempt to compute the bounding box of an SVG element using Apache Batik (via Java bridge).
 *
 * Serializes the element to SVG XML (using outerHTML), sends it to the Java-side
 * BatikBBoxService which parses it with Batik's GVT tree, and returns accurate
 * geometry bounds computed by Java2D's text layout engine.
 *
 * This is the preferred path for <text> elements (with <tspan> children), where
 * JavaScript-side heuristics are error-prone (em units, absolute tspan positioning,
 * font metrics, text-anchor).
 *
 * @param {Object} el  Shim DOM element
 * @returns {{x:number, y:number, w:number, h:number}|null}  BBox or null if Batik fails
 */
function _computeBBoxViaBatik(el) {
    if (typeof javaBridge === 'undefined' || !javaBridge.computeSvgBBox) return null;

    try {
        // Serialize element to SVG XML via outerHTML
        var svgXml = el.outerHTML;
        if (!svgXml || svgXml.length < 5) return null;

        // Skip very large fragments (groups with many children) — too expensive for Batik
        // and the fragment might reference external defs/styles that Batik can't resolve.
        if (svgXml.length > 5000) return null;

        var result = javaBridge.computeSvgBBox(svgXml);
        if (!result || result === '') return null;

        var parts = ('' + result).split(',');
        if (parts.length < 4) return null;

        var bx = parseFloat(parts[0]);
        var by = parseFloat(parts[1]);
        var bw = parseFloat(parts[2]);
        var bh = parseFloat(parts[3]);

        // Sanity check: Batik must return reasonable values
        if (isNaN(bw) || isNaN(bh) || bw <= 0 || bh <= 0) return null;
        if (isNaN(bx)) bx = 0;
        if (isNaN(by)) by = 0;

        // Reject clearly wrong results (e.g. negative dimensions or huge offsets
        // that indicate Batik failed to parse the fragment correctly)
        if (bw > 10000 || bh > 10000) return null;

        return { x: bx, y: by, w: bw, h: bh };
    } catch (e) {
        // Batik failed — fall through to JS heuristic
        return null;
    }
}

/**
 * Resolve an SVG length value to pixels.
 * Handles raw numbers, 'px', 'em', and '%' units.
 * @param {string|number} val  The attribute value (e.g. "12", "1.1em", "50%", "16px")
 * @param {number} fontSize    The resolved font-size in px for em conversion
 * @param {number} refSize     Reference dimension for % conversion (default 0)
 * @returns {number} Value in pixels, or NaN if not parseable
 */
function _resolveSvgLength(val, fontSize, refSize) {
    if (val === undefined || val === null || val === '') return NaN;
    var s = String(val).trim();
    if (s.indexOf('em') >= 0) {
        return parseFloat(s) * (fontSize || 16);
    }
    if (s.indexOf('%') >= 0) {
        return parseFloat(s) / 100 * (refSize || 0);
    }
    // 'px', 'pt', bare numbers — parseFloat handles the numeric prefix
    return parseFloat(s);
}

function _computeElementDims(el) {
    var tag = (el.tagName || '').toLowerCase();
    var attrs = el._attrs || {};

    // display:none elements have zero dimensions (critical for Cytoscape headless detection)
    var styleAttr = attrs['style'] || '';
    if (el.style && el.style._props && el.style._props['display'] === 'none') {
        return { x: 0, y: 0, w: 0, h: 0 };
    }
    if (styleAttr.indexOf('display') >= 0 && styleAttr.match(/display\s*:\s*none/i)) {
        return { x: 0, y: 0, w: 0, h: 0 };
    }

    // Helper to read numeric attribute (plain px, no unit conversion needed
    // for geometric attributes like x, y, width, height, r, cx, cy etc.)
    var elFontSize = _resolveFontSize(el) || 16;
    function num(name) {
        var v = attrs[name];
        if (v === undefined || v === null || v === '') return NaN;
        return _resolveSvgLength(v, elFontSize, 0);
    }

    // 1) rect — explicit width/height
    if (tag === 'rect') {
        var rw = num('width'), rh = num('height'), rx = num('x'), ry = num('y');
        if (!isNaN(rw) && !isNaN(rh)) {
            return { x: isNaN(rx) ? 0 : rx, y: isNaN(ry) ? 0 : ry, w: rw, h: rh };
        }
        // Rect without explicit width/height has 0×0 dimensions (SVG spec).
        // Must NOT fall through to the generic 20×20 fallback — that inflates
        // parent group measurements (e.g. Mermaid ER diagram background rects).
        return { x: isNaN(rx) ? 0 : rx, y: isNaN(ry) ? 0 : ry, w: 0, h: 0 };
    }

    // 2) circle — use r
    if (tag === 'circle') {
        var r = num('r'), cx = num('cx'), cy = num('cy');
        if (!isNaN(r)) {
            cx = isNaN(cx) ? 0 : cx; cy = isNaN(cy) ? 0 : cy;
            return { x: cx - r, y: cy - r, w: 2 * r, h: 2 * r };
        }
    }

    // 3) ellipse — use rx, ry
    if (tag === 'ellipse') {
        var erx = num('rx'), ery = num('ry'), ecx = num('cx'), ecy = num('cy');
        if (!isNaN(erx) && !isNaN(ery)) {
            ecx = isNaN(ecx) ? 0 : ecx; ecy = isNaN(ecy) ? 0 : ecy;
            return { x: ecx - erx, y: ecy - ery, w: 2 * erx, h: 2 * ery };
        }
    }

    // 4) polygon — parse points attribute
    if (tag === 'polygon' || tag === 'polyline') {
        var pts = attrs['points'];
        if (pts) {
            var coords = pts.trim().split(/[\s,]+/);
            var minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
            for (var i = 0; i + 1 < coords.length; i += 2) {
                var px = parseFloat(coords[i]), py = parseFloat(coords[i + 1]);
                if (!isNaN(px) && !isNaN(py)) {
                    if (px < minX) minX = px; if (px > maxX) maxX = px;
                    if (py < minY) minY = py; if (py > maxY) maxY = py;
                }
            }
            if (minX < Infinity) {
                // Also account for transform on this element
                var tw = maxX - minX, th = maxY - minY;
                var tOff = _parseTranslate(el);
                return { x: minX + tOff[0], y: minY + tOff[1], w: tw, h: th };
            }
        }
    }

    // 4b) path — parse d attribute for bounding box
    if (tag === 'path') {
        var pd = attrs['d'];
        if (pd) {
            var pathBox = _parsePathBBox(pd);
            if (pathBox) {
                var ptOff = _parseTranslate(el);
                return { x: pathBox.minX + ptOff[0], y: pathBox.minY + ptOff[1],
                         w: pathBox.maxX - pathBox.minX, h: pathBox.maxY - pathBox.minY };
            }
        }
    }

    // 5) line — x1,y1,x2,y2
    if (tag === 'line') {
        var x1 = num('x1'), y1 = num('y1'), x2 = num('x2'), y2 = num('y2');
        if (!isNaN(x1) && !isNaN(y1) && !isNaN(x2) && !isNaN(y2)) {
            return { x: Math.min(x1, x2), y: Math.min(y1, y2),
                     w: Math.abs(x2 - x1) || 1, h: Math.abs(y2 - y1) || 1 };
        }
    }

    // 6) text — estimate from content; height based on actual font metrics.
    //    SVG text positioning rules:
    //    - <text x="X" y="Y"> sets the initial current text position
    //    - <tspan x="X"> RESETS the horizontal position (absolute)
    //    - <tspan y="Y"> RESETS the vertical position (absolute)
    //    - <tspan dx="D"> shifts horizontally relative to current
    //    - <tspan dy="D"> shifts vertically relative to current
    //    - em units are relative to the element's font-size
    if (tag === 'text') {
        // ── NOTE: Batik BBox is available via _computeBBoxViaBatik(el) but
        //    currently DISABLED for <text> elements because the serialized
        //    SVG fragment loses inherited styles from parent elements
        //    (text-anchor, font-family, font-size).  This causes text to
        //    shift right when text-anchor:middle is inherited from a <g>.
        //    TODO: Re-enable once _computeBBoxViaBatik enriches fragments
        //          with resolved inherited styles before sending to Batik.
        // ── JS heuristic (correctly walks DOM tree for inherited styles) ─

        // Collect tspan children (direct children only, per SVG spec)
        var tspans = [];
        if (el.childNodes) {
            for (var ti = 0; ti < el.childNodes.length; ti++) {
                var tc = el.childNodes[ti];
                if (tc.tagName && tc.tagName.toLowerCase() === 'tspan') tspans.push(tc);
            }
        }

        var textFs = _resolveFontSize(el) || 16;
        var anchor = _resolveTextAnchor(el);

        if (tspans.length > 0) {
            // Multi-span text: proper SVG text layout
            var textMinX = Infinity, textMinY = Infinity, textMaxX = -Infinity, textMaxY = -Infinity;

            // Initial current position from <text x="" y="">
            var baseTextX = _resolveSvgLength(attrs['x'], textFs, 0);
            var baseTextY = _resolveSvgLength(attrs['y'], textFs, 0);
            if (isNaN(baseTextX)) baseTextX = 0;
            if (isNaN(baseTextY)) baseTextY = 0;

            var curX = baseTextX;
            var curY = baseTextY;

            for (var si = 0; si < tspans.length; si++) {
                var span = tspans[si];
                var spanAttrs = span._attrs || {};
                var spanFs = _resolveFontSize(span) || textFs;
                var spanMetrics = _measureTextMetrics(span);
                if (spanMetrics.width <= 0) continue;

                // tspan x → absolute reset of horizontal position
                var spanXAttr = spanAttrs['x'];
                if (spanXAttr !== undefined && spanXAttr !== null && spanXAttr !== '') {
                    curX = _resolveSvgLength(spanXAttr, spanFs, 0);
                    if (isNaN(curX)) curX = baseTextX;
                }

                // tspan y → absolute reset of vertical position
                var spanYAttr = spanAttrs['y'];
                if (spanYAttr !== undefined && spanYAttr !== null && spanYAttr !== '') {
                    var resolvedY = _resolveSvgLength(spanYAttr, spanFs, 0);
                    if (!isNaN(resolvedY)) curY = resolvedY;
                }

                // tspan dx → relative horizontal shift
                var spanDx = spanAttrs['dx'];
                if (spanDx !== undefined && spanDx !== null && spanDx !== '') {
                    curX += _resolveSvgLength(spanDx, spanFs, 0) || 0;
                }

                // tspan dy → relative vertical shift
                var spanDy = spanAttrs['dy'];
                if (spanDy !== undefined && spanDy !== null && spanDy !== '') {
                    curY += _resolveSvgLength(spanDy, spanFs, 0) || 0;
                }

                // Calculate bounding box of this span at current position
                var sx;
                if (anchor === 'middle') {
                    sx = curX - spanMetrics.width / 2;
                } else if (anchor === 'end') {
                    sx = curX - spanMetrics.width;
                } else {
                    sx = curX;
                }
                // SVG text y is the baseline — bounding box top is y - ascent
                var sy = curY - spanMetrics.ascent;

                if (sx < textMinX) textMinX = sx;
                if (sy < textMinY) textMinY = sy;
                if (sx + spanMetrics.width > textMaxX) textMaxX = sx + spanMetrics.width;
                if (sy + spanMetrics.height > textMaxY) textMaxY = sy + spanMetrics.height;
            }
            if (textMinX < Infinity && textMaxX > textMinX) {
                return { x: textMinX, y: textMinY,
                         w: textMaxX - textMinX, h: textMaxY - textMinY };
            }
        }

        // Single-line text (no tspan children, or tspans had no text)
        var textMetrics = _measureTextMetrics(el);
        if (textMetrics.width > 0) {
            var tx = num('x'), ty = num('y');
            var anchor1 = _resolveTextAnchor(el);
            var bx;
            if (anchor1 === 'middle') {
                bx = isNaN(tx) ? -textMetrics.width / 2 : tx - textMetrics.width / 2;
            } else if (anchor1 === 'end') {
                bx = isNaN(tx) ? -textMetrics.width : tx - textMetrics.width;
            } else {
                bx = isNaN(tx) ? 0 : tx;
            }
            return { x: bx,
                     y: isNaN(ty) ? -textMetrics.ascent : ty - textMetrics.ascent,
                     w: textMetrics.width, h: textMetrics.height };
        }
        // Text element with no measurable content → 0×0 (must not fall to 20×20 fallback)
        return { x: 0, y: 0, w: 0, h: 0 };
    }
    if (tag === 'tspan') {
        var tspanMetrics = _measureTextMetrics(el);
        if (tspanMetrics.width > 0) {
            var tsFs = _resolveFontSize(el) || 16;
            var tsXAttr = attrs['x'];
            var tsYAttr = attrs['y'];
            var tsx = (tsXAttr !== undefined && tsXAttr !== null && tsXAttr !== '')
                      ? _resolveSvgLength(tsXAttr, tsFs, 0) : NaN;
            var tsy = (tsYAttr !== undefined && tsYAttr !== null && tsYAttr !== '')
                      ? _resolveSvgLength(tsYAttr, tsFs, 0) : NaN;

            var tAnchor = _resolveTextAnchor(el);
            var tbx;
            if (tAnchor === 'middle') {
                tbx = isNaN(tsx) ? -tspanMetrics.width / 2 : tsx - tspanMetrics.width / 2;
            } else if (tAnchor === 'end') {
                tbx = isNaN(tsx) ? -tspanMetrics.width : tsx - tspanMetrics.width;
            } else {
                tbx = isNaN(tsx) ? 0 : tsx;
            }
            // Account for dy offset
            var tsDy = attrs['dy'];
            var dyOffset = 0;
            if (tsDy !== undefined && tsDy !== null && tsDy !== '') {
                dyOffset = _resolveSvgLength(tsDy, tsFs, 0) || 0;
            }
            var tby = isNaN(tsy) ? -tspanMetrics.ascent + dyOffset : tsy - tspanMetrics.ascent + dyOffset;
            return { x: tbx, y: tby,
                     w: tspanMetrics.width, h: tspanMetrics.height };
        }
        // Tspan with no measurable content → 0×0 (must not fall to 20×20 fallback)
        return { x: 0, y: 0, w: 0, h: 0 };
    }

    // 7) foreignObject — explicit width/height, or estimate from text content
    if (tag === 'foreignobject') {
        var fw = num('width'), fh = num('height'), fx = num('x'), fy = num('y');
        if (!isNaN(fw) && !isNaN(fh) && fw > 0 && fh > 0) {
            return { x: isNaN(fx) ? 0 : fx, y: isNaN(fy) ? 0 : fy, w: fw, h: fh };
        }
        // No dimensions set yet — estimate from text content (Mermaid measures before setting w/h)
        var foText = _estimateTextWidth(el);
        if (foText > 0) {
            var fs7 = _resolveFontSize(el) || 16;
            return { x: isNaN(fx) ? 0 : fx, y: isNaN(fy) ? 0 : fy, w: foText, h: Math.round(fs7) };
        }
        // foreignObject without content or dimensions → 0×0
        return { x: isNaN(fx) ? 0 : fx, y: isNaN(fy) ? 0 : fy, w: 0, h: 0 };
    }

    // 7b) HTML elements (div, span, p, label, etc.) — used inside foreignObject
    if (tag === 'div' || tag === 'span' || tag === 'p' || tag === 'label' || tag === 'b' || tag === 'i') {
        var htw = _estimateTextWidth(el);
        if (htw > 0) {
            var fs7b = _resolveFontSize(el) || 16;
            return { x: 0, y: 0, w: htw, h: Math.round(fs7b) };
        }
    }

    // 7c) SVG root element — resolve from attributes or parent, NOT from children.
    //     Mermaid's D3 reads `element.offsetWidth` during early layout when the SVG
    //     only has partial content (e.g. a title text).  Aggregating children at that
    //     point gives ~130px (text width) instead of the correct 1600px container width.
    //     This behaviour is correct for offsetWidth/offsetHeight/clientWidth/clientHeight.
    //     getBBox() is handled separately via _computeSvgContentBBox() — see below.
    if (tag === 'svg') {
        var svgW = attrs['width'];
        var svgH = attrs['height'];

        // Explicit pixel dimensions (e.g. width="800" height="600")
        if (svgW && svgH) {
            var ew = parseFloat(svgW);
            var eh = parseFloat(svgH);

            // Percentage → resolve from parent (avoiding infinite recursion:
            // parent's children-aggregate would call us back, but we return
            // before reaching that path)
            if (String(svgW).indexOf('%') >= 0 && el.parentNode && el.parentNode.nodeType === 1) {
                // Temporarily detach from parent to prevent recursion
                var savedParent = el.parentNode;
                var savedNext = el.nextSibling;
                try {
                    savedParent.removeChild(el);
                    var parentDims = _computeElementDims(savedParent);
                    ew = parseFloat(svgW) / 100 * parentDims.w;
                    eh = parseFloat(svgH) / 100 * parentDims.h;
                    if (isNaN(eh) || eh <= 0) eh = parentDims.h;
                } finally {
                    if (savedNext) savedParent.insertBefore(el, savedNext);
                    else savedParent.appendChild(el);
                }
            }

            if (!isNaN(ew) && ew > 0 && !isNaN(eh) && eh > 0) {
                return { x: 0, y: 0, w: ew, h: eh };
            }
            // width="100%" but parent resolution failed → try viewBox or default
        }

        // ViewBox dimensions
        var svgVb = attrs['viewBox'];
        if (svgVb) {
            var vbParts = String(svgVb).trim().split(/\s+/);
            if (vbParts.length >= 4) {
                var vbW = parseFloat(vbParts[2]);
                var vbH = parseFloat(vbParts[3]);
                if (vbW > 0 && vbH > 0) {
                    return { x: parseFloat(vbParts[0]) || 0, y: parseFloat(vbParts[1]) || 0,
                             w: vbW, h: vbH };
                }
            }
        }

        // Fallback: container-sized default
        return { x: 0, y: 0, w: 1600, h: 900 };
    }

    // 8) g / other containers — aggregate child bboxes with proper min/max
    if (el.childNodes && el.childNodes.length > 0) {
        var cMinX = Infinity, cMinY = Infinity, cMaxX = -Infinity, cMaxY = -Infinity;
        var found = false;
        for (var i = 0; i < el.childNodes.length; i++) {
            var child = el.childNodes[i];
            if (child.getBBox) {
                var cb = child.getBBox();
                if (cb.w === undefined) { cb.w = cb.width; cb.h = cb.height; }
                if (cb.w > 0 || cb.h > 0) {
                    // Apply child's full transform (translate + rotate + scale)
                    var tb = _transformBBox(cb, child);
                    var cx1 = tb.x, cy1 = tb.y;
                    var cx2 = cx1 + tb.w, cy2 = cy1 + tb.h;
                    if (cx1 < cMinX) cMinX = cx1;
                    if (cy1 < cMinY) cMinY = cy1;
                    if (cx2 > cMaxX) cMaxX = cx2;
                    if (cy2 > cMaxY) cMaxY = cy2;
                    found = true;
                }
            }
        }
        if (found && cMaxX > cMinX && cMaxY > cMinY) {
            return { x: cMinX, y: cMinY, w: cMaxX - cMinX, h: cMaxY - cMinY };
        }
    }

    // Fallback: text-based estimate or reasonable container default
    var textLen = _estimateTextWidth(el);
    if (textLen > 0) {
        var fallbackFs = _resolveFontSize(el) || 16;
        return { x: 0, y: 0, w: textLen, h: Math.round(fallbackFs * 1.2) };
    }
    // Container elements and SVG roots get large defaults
    // so Mermaid can compute proper layout bounds (especially Gantt charts).
    // 1600×900 prevents D3 scale inversion on Gantt charts where long
    // task labels eat up horizontal margin space.
    if (tag === 'div' || tag === 'body' || tag === 'html' || tag === 'canvas' || tag === 'section'
        || tag === 'article' || tag === 'main' || tag === 'nav' || tag === 'header'
        || tag === 'footer' || tag === 'aside') {
        return { x: 0, y: 0, w: 1600, h: 900 };
    }
    // g (group) elements with no children — small default
    if (tag === 'g') {
        return { x: 0, y: 0, w: 0, h: 0 };
    }
    return { x: 0, y: 0, w: 20, h: 20 };
}

/**
 * Compute the content bounding box of an SVG root element by aggregating
 * all child bounding boxes.  This is what getBBox() should return for <svg>
 * elements — the actual rendered content bounds, NOT the declared container
 * dimensions.
 *
 * This is separate from _computeElementDims because offsetWidth/offsetHeight
 * must return the declared container size (Mermaid D3 reads those during
 * early layout), while getBBox() must return content bounds (Mermaid uses
 * those for final viewBox computation).
 */
function _computeSvgContentBBox(el) {
    if (!el.childNodes || el.childNodes.length === 0) {
        // No children yet — return declared dimensions as fallback
        return _computeElementDims(el);
    }
    var cMinX = Infinity, cMinY = Infinity, cMaxX = -Infinity, cMaxY = -Infinity;
    var found = false;
    for (var i = 0; i < el.childNodes.length; i++) {
        var child = el.childNodes[i];
        // Skip <style> and <defs> — they don't contribute to rendered bounds
        var childTag = (child.tagName || '').toLowerCase();
        if (childTag === 'style' || childTag === 'defs') continue;
        if (child.getBBox) {
            var cb = child.getBBox();
            if (cb.w === undefined) { cb.w = cb.width; cb.h = cb.height; }
            if (cb.w > 0 || cb.h > 0) {
                // Apply child's full transform (translate + rotate + scale)
                var tb = _transformBBox(cb, child);
                var cx1 = tb.x, cy1 = tb.y;
                var cx2 = cx1 + tb.w, cy2 = cy1 + tb.h;
                if (cx1 < cMinX) cMinX = cx1;
                if (cy1 < cMinY) cMinY = cy1;
                if (cx2 > cMaxX) cMaxX = cx2;
                if (cy2 > cMaxY) cMaxY = cy2;
                found = true;
            }
        }
    }
    if (found && cMaxX > cMinX && cMaxY > cMinY) {
        return { x: cMinX, y: cMinY, w: cMaxX - cMinX, h: cMaxY - cMinY };
    }
    // No visible children — fall back to declared dimensions
    return _computeElementDims(el);
}

/**
 * Parse an SVG path "d" attribute and compute its bounding box.
 * Handles absolute and relative M, L, H, V, C, S, Q, T, A, Z commands.
 * For bezier curves, control points are included (conservative bbox).
 * Returns {minX, minY, maxX, maxY} or null if parsing fails.
 */
function _parsePathBBox(d) {
    if (!d) return null;
    var minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
    var found = false;
    var curX = 0, curY = 0;
    var startX = 0, startY = 0;

    function update(x, y) {
        if (x < minX) minX = x;
        if (y < minY) minY = y;
        if (x > maxX) maxX = x;
        if (y > maxY) maxY = y;
        found = true;
    }

    // Tokenize: split by command letters, keeping the command
    var tokens = d.match(/[MmLlHhVvCcSsQqTtAaZz][^MmLlHhVvCcSsQqTtAaZz]*/g);
    if (!tokens) return null;

    for (var i = 0; i < tokens.length; i++) {
        var token = tokens[i].trim();
        var cmd = token.charAt(0);
        var args = token.substring(1).trim();
        var nums = args.match(/-?[\d]+(?:\.[\d]*)?(?:[eE][+-]?[\d]+)?/g);
        var values = [];
        if (nums) {
            for (var j = 0; j < nums.length; j++) {
                values.push(parseFloat(nums[j]));
            }
        }

        var isRel = cmd === cmd.toLowerCase();
        var CMD = cmd.toUpperCase();

        switch (CMD) {
            case 'M':
                for (var k = 0; k + 1 < values.length; k += 2) {
                    var mx = isRel ? curX + values[k] : values[k];
                    var my = isRel ? curY + values[k + 1] : values[k + 1];
                    update(mx, my);
                    curX = mx; curY = my;
                    if (k === 0) { startX = mx; startY = my; }
                    // After first pair, implicit L commands
                }
                break;
            case 'L':
            case 'T':
                for (var k = 0; k + 1 < values.length; k += 2) {
                    var lx = isRel ? curX + values[k] : values[k];
                    var ly = isRel ? curY + values[k + 1] : values[k + 1];
                    update(lx, ly);
                    curX = lx; curY = ly;
                }
                break;
            case 'H':
                for (var k = 0; k < values.length; k++) {
                    var hx = isRel ? curX + values[k] : values[k];
                    update(hx, curY);
                    curX = hx;
                }
                break;
            case 'V':
                for (var k = 0; k < values.length; k++) {
                    var vy = isRel ? curY + values[k] : values[k];
                    update(curX, vy);
                    curY = vy;
                }
                break;
            case 'C':
                for (var k = 0; k + 5 < values.length; k += 6) {
                    for (var p = 0; p < 3; p++) {
                        var cx = isRel ? curX + values[k + p * 2] : values[k + p * 2];
                        var cy = isRel ? curY + values[k + p * 2 + 1] : values[k + p * 2 + 1];
                        update(cx, cy);
                    }
                    curX = isRel ? curX + values[k + 4] : values[k + 4];
                    curY = isRel ? curY + values[k + 5] : values[k + 5];
                }
                break;
            case 'S':
                for (var k = 0; k + 3 < values.length; k += 4) {
                    for (var p = 0; p < 2; p++) {
                        var sx = isRel ? curX + values[k + p * 2] : values[k + p * 2];
                        var sy = isRel ? curY + values[k + p * 2 + 1] : values[k + p * 2 + 1];
                        update(sx, sy);
                    }
                    curX = isRel ? curX + values[k + 2] : values[k + 2];
                    curY = isRel ? curY + values[k + 3] : values[k + 3];
                }
                break;
            case 'Q':
                for (var k = 0; k + 3 < values.length; k += 4) {
                    for (var p = 0; p < 2; p++) {
                        var qx = isRel ? curX + values[k + p * 2] : values[k + p * 2];
                        var qy = isRel ? curY + values[k + p * 2 + 1] : values[k + p * 2 + 1];
                        update(qx, qy);
                    }
                    curX = isRel ? curX + values[k + 2] : values[k + 2];
                    curY = isRel ? curY + values[k + 3] : values[k + 3];
                }
                break;
            case 'A':
                for (var k = 0; k + 6 < values.length; k += 7) {
                    var ax = isRel ? curX + values[k + 5] : values[k + 5];
                    var ay = isRel ? curY + values[k + 6] : values[k + 6];
                    update(ax, ay);
                    // Conservative: include arc radius extent
                    var arx = values[k], ary = values[k + 1];
                    update(ax - arx, ay - ary);
                    update(ax + arx, ay + ary);
                    curX = ax; curY = ay;
                }
                break;
            case 'Z':
                curX = startX; curY = startY;
                break;
        }
    }

    if (!found || minX >= Infinity) return null;
    return { minX: minX, minY: minY, maxX: maxX, maxY: maxY };
}

// Parse transform attribute and extract the net [tx, ty] translation.
// Supports: translate(x), translate(x,y), matrix(a,b,c,d,e,f),
// scale(s), scale(sx,sy), rotate(a), and combined transforms.
function _parseTransform(el) {
    var t = el._attrs && el._attrs['transform'];
    if (!t && el.getAttribute) t = el.getAttribute('transform');
    if (!t) return [0, 0];
    // Accumulate net translation by processing each transform function
    var tx = 0, ty = 0;
    var re = /(translate|matrix|scale|rotate|skewX|skewY)\s*\(([^)]*)\)/gi;
    var m;
    while ((m = re.exec(t)) !== null) {
        var fn = m[1].toLowerCase();
        var args = m[2].replace(/,/g, ' ').trim().split(/\s+/).map(parseFloat);
        if (fn === 'translate') {
            tx += args[0] || 0;
            ty += args.length > 1 ? (args[1] || 0) : 0;
        } else if (fn === 'matrix') {
            // matrix(a,b,c,d,e,f) — e=tx, f=ty
            if (args.length >= 6) {
                tx += args[4] || 0;
                ty += args[5] || 0;
            }
        }
        // scale, rotate, skew affect shape but not absolute position offset
        // for our bounding box purposes we only care about translation
    }
    return [tx, ty];
}

// Backwards-compatible alias
function _parseTranslate(el) { return _parseTransform(el); }

/**
 * Transform a local bounding box through an element's full transform attribute
 * (translate, rotate, scale, matrix) and return the axis-aligned bounding box
 * of the transformed rectangle.
 *
 * This is used when aggregating child bboxes in parent containers (<g>, <svg>).
 * Unlike _parseTranslate which only extracts translation, this correctly handles
 * rotation (e.g. rotated Y-axis labels) and scale by transforming all 4 corners
 * of the bbox and computing their AABB.
 *
 * @param {{x:number, y:number, w:number, h:number}} box  Local bounding box
 * @param {Object} el  DOM element whose transform to apply
 * @returns {{x:number, y:number, w:number, h:number}} Transformed AABB
 */
function _transformBBox(box, el) {
    var t = el._attrs && el._attrs['transform'];
    if (!t && el.getAttribute) t = el.getAttribute('transform');
    if (!t) return box;  // no transform → return as-is

    // Build a 2D affine transform matrix [a,b,c,d,tx,ty] (identity start)
    var a = 1, b = 0, c = 0, d = 1, tx = 0, ty = 0;

    var re = /(translate|matrix|scale|rotate)\s*\(([^)]*)\)/gi;
    var m;
    while ((m = re.exec(t)) !== null) {
        var fn = m[1].toLowerCase();
        var args = m[2].replace(/,/g, ' ').trim().split(/\s+/).map(parseFloat);
        var na, nb, nc, nd, ntx, nty;

        if (fn === 'translate') {
            na = 1; nb = 0; nc = 0; nd = 1;
            ntx = args[0] || 0;
            nty = args.length > 1 ? (args[1] || 0) : 0;
        } else if (fn === 'scale') {
            var sx = args[0] || 1;
            var sy = args.length > 1 ? (args[1] || sx) : sx;
            na = sx; nb = 0; nc = 0; nd = sy; ntx = 0; nty = 0;
        } else if (fn === 'rotate') {
            var rad = (args[0] || 0) * Math.PI / 180;
            var cosA = Math.cos(rad), sinA = Math.sin(rad);
            na = cosA; nb = sinA; nc = -sinA; nd = cosA; ntx = 0; nty = 0;
        } else if (fn === 'matrix') {
            na = args[0] || 1; nb = args[1] || 0;
            nc = args[2] || 0; nd = args[3] || 1;
            ntx = args[4] || 0; nty = args[5] || 0;
        } else { continue; }

        // Compose: current = current * new
        var ra = a * na + c * nb,  rb = b * na + d * nb;
        var rc = a * nc + c * nd,  rd = b * nc + d * nd;
        var rtx = a * ntx + c * nty + tx, rty = b * ntx + d * nty + ty;
        a = ra; b = rb; c = rc; d = rd; tx = rtx; ty = rty;
    }

    // Fast path: pure translation (no rotation/scale)
    if (a === 1 && b === 0 && c === 0 && d === 1) {
        return { x: box.x + tx, y: box.y + ty, w: box.w, h: box.h };
    }

    // Transform the 4 corners and find their axis-aligned bounding box
    var corners = [
        [box.x,         box.y],
        [box.x + box.w, box.y],
        [box.x + box.w, box.y + box.h],
        [box.x,         box.y + box.h]
    ];
    var minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
    for (var i = 0; i < 4; i++) {
        var px = corners[i][0], py = corners[i][1];
        var rx = a * px + c * py + tx;
        var ry = b * px + d * py + ty;
        if (rx < minX) minX = rx;
        if (ry < minY) minY = ry;
        if (rx > maxX) maxX = rx;
        if (ry > maxY) maxY = ry;
    }
    return { x: minX, y: minY, w: maxX - minX, h: maxY - minY };
}

// ── Minimal HTML/SVG parser for innerHTML ───────────────────────────────────
// DOMPurify sets innerHTML on a document's body and then walks the DOM tree
// with createNodeIterator. We need to parse the HTML string into actual DOM nodes.
// This is intentionally simple and handles basic well-formed markup.
function _parseHTML(html, ownerDoc, parentNs) {
    if (!html || typeof html !== 'string') return [];
    var nodes = [];
    var pos = 0;
    var len = html.length;

    while (pos < len) {
        var lt = html.indexOf('<', pos);
        if (lt < 0) {
            // remaining text
            var rest = html.substring(pos);
            if (rest) {
                var tn = { nodeType: 3, nodeName: '#text', textContent: rest, ownerDocument: ownerDoc,
                           cloneNode: function() { return { nodeType: 3, nodeName: '#text', textContent: this.textContent, ownerDocument: ownerDoc }; } };
                nodes.push(tn);
            }
            break;
        }
        // text before <
        if (lt > pos) {
            var txt = html.substring(pos, lt);
            if (txt) {
                var tNode = { nodeType: 3, nodeName: '#text', textContent: txt, ownerDocument: ownerDoc,
                              cloneNode: function() { return { nodeType: 3, nodeName: '#text', textContent: this.textContent, ownerDocument: ownerDoc }; } };
                nodes.push(tNode);
            }
        }
        // comment?
        if (html.substring(lt, lt + 4) === '<!--') {
            var commentEnd = html.indexOf('-->', lt + 4);
            if (commentEnd < 0) commentEnd = len;
            var commentData = html.substring(lt + 4, commentEnd);
            nodes.push({ nodeType: 8, nodeName: '#comment', textContent: commentData, ownerDocument: ownerDoc });
            pos = commentEnd + 3;
            continue;
        }
        // closing tag? skip
        if (html.charAt(lt + 1) === '/') {
            var closEnd = html.indexOf('>', lt + 2);
            if (closEnd < 0) closEnd = len - 1;
            pos = closEnd + 1;
            // return collected nodes (they are children up to this closing tag)
            break;
        }
        // DOCTYPE / processing instructions — skip
        if (html.charAt(lt + 1) === '!' || html.charAt(lt + 1) === '?') {
            var piEnd = html.indexOf('>', lt + 2);
            if (piEnd < 0) piEnd = len - 1;
            pos = piEnd + 1;
            continue;
        }
        // opening tag
        var gt = html.indexOf('>', lt + 1);
        if (gt < 0) { pos = len; break; }
        var tagContent = html.substring(lt + 1, gt);
        var selfClosing = tagContent.charAt(tagContent.length - 1) === '/';
        if (selfClosing) tagContent = tagContent.substring(0, tagContent.length - 1).trim();
        // Extract tag name
        var spaceIdx = tagContent.search(/[\s\/]/);
        var tagName = spaceIdx > 0 ? tagContent.substring(0, spaceIdx) : tagContent;
        tagName = tagName.trim();
        if (!tagName) { pos = gt + 1; continue; }
        // Determine namespace
        var ns = parentNs || XHTML_NS;
        if (tagName.toLowerCase() === 'svg') ns = SVG_NS;
        else if (parentNs === SVG_NS) ns = SVG_NS;
        // Create element (createDomElement is defined below, but we need it here)
        // Use a lightweight node instead to avoid circular dependency
        var child = _createParseNode(tagName, ns, ownerDoc);
        // Parse attributes
        var attrStr = spaceIdx > 0 ? tagContent.substring(spaceIdx) : '';
        var attrRe = /([a-zA-Z_:][a-zA-Z0-9_.:-]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|(\S+))/g;
        var am;
        while ((am = attrRe.exec(attrStr)) !== null) {
            var aName = am[1];
            var aVal = am[2] !== undefined ? am[2] : (am[3] !== undefined ? am[3] : am[4]);
            if (child._attrs) child._attrs[aName] = aVal;
            if (aName === 'id') child.id = aVal;
            if (aName === 'class') child.className = aVal;
        }
        // Void / self-closing elements
        var VOID_ELEMENTS = { area:1, base:1, br:1, col:1, embed:1, hr:1, img:1, input:1,
            link:1, meta:1, param:1, source:1, track:1, wbr:1 };
        if (selfClosing || VOID_ELEMENTS[tagName.toLowerCase()]) {
            nodes.push(child);
            pos = gt + 1;
            continue;
        }
        // Find matching close tag and parse children
        pos = gt + 1;
        // Recursively parse children until we find </tagName>
        var closeTag = '</' + tagName;
        var closeTagLower = '</' + tagName.toLowerCase();
        var depth = 1;
        var searchPos = pos;
        var contentEnd = -1;
        // Simple approach: find the matching close tag
        while (searchPos < len) {
            var nextLt = html.indexOf('<', searchPos);
            if (nextLt < 0) { contentEnd = len; break; }
            var peek = html.substring(nextLt, nextLt + closeTag.length + 1).toLowerCase();
            if (peek === closeTagLower.toLowerCase() + '>' || peek.indexOf(closeTagLower.toLowerCase()) === 0) {
                var afterClose = html.indexOf('>', nextLt + closeTag.length);
                if (afterClose < 0) afterClose = len - 1;
                contentEnd = nextLt;
                pos = afterClose + 1;
                break;
            }
            searchPos = nextLt + 1;
        }
        if (contentEnd < 0) contentEnd = len;
        // Parse inner content as children
        var innerHtml = html.substring(gt + 1, contentEnd);
        if (innerHtml) {
            var childNodes = _parseHTML(innerHtml, ownerDoc, ns);
            for (var ci = 0; ci < childNodes.length; ci++) {
                var cn = childNodes[ci];
                cn.parentNode = child;
                child.childNodes.push(cn);
                if (cn.nodeType === 1) child.children.push(cn);
            }
            if (child.childNodes.length > 0) {
                child.firstChild = child.childNodes[0];
                child.lastChild = child.childNodes[child.childNodes.length - 1];
            }
        }
        nodes.push(child);
    }
    return nodes;
}

// Lightweight DOM node for innerHTML parsing (used before full createDomElement is available)
function _createParseNode(tagName, ns, ownerDoc) {
    var el = {};
    el.nodeType = 1;
    if (ns === SVG_NS) {
        var upper = tagName ? tagName.toUpperCase() : 'SVG';
        el.tagName = SVG_CAMEL_CASE_TAGS[upper] || (tagName || 'svg');
    } else {
        el.tagName = tagName ? tagName.toUpperCase() : 'DIV';
    }
    el.nodeName = el.tagName;
    el.namespaceURI = ns || XHTML_NS;
    el.ownerDocument = ownerDoc;
    el.className = '';
    el.id = '';
    el.childNodes = [];
    el.children = [];
    el.parentNode = null;
    el.firstChild = null;
    el.lastChild = null;
    el.nextSibling = null;
    el.previousSibling = null;
    el._attrs = {};
    el.attributes = [];
    el.textContent = '';
    el.nodeValue = null;
    el.hasChildNodes = function() { return el.childNodes && el.childNodes.length > 0; };
    el.removeChild = function(child) {
        var idx = el.childNodes.indexOf(child);
        if (idx >= 0) el.childNodes.splice(idx, 1);
        return child;
    };
    el.appendChild = function(child) {
        el.childNodes.push(child);
        if (child.nodeType === 1) el.children.push(child);
        child.parentNode = el;
        el.firstChild = el.childNodes[0];
        el.lastChild = el.childNodes[el.childNodes.length - 1];
        return child;
    };
    el.insertBefore = function(newNode, refNode) {
        if (!refNode) return el.appendChild(newNode);
        var idx = el.childNodes.indexOf(refNode);
        if (idx >= 0) {
            el.childNodes.splice(idx, 0, newNode);
            if (newNode.nodeType === 1) {
                var eIdx = el.children.indexOf(refNode);
                if (eIdx >= 0) el.children.splice(eIdx, 0, newNode);
                else el.children.push(newNode);
            }
        } else {
            el.childNodes.push(newNode);
            if (newNode.nodeType === 1) el.children.push(newNode);
        }
        newNode.parentNode = el;
        el.firstChild = el.childNodes[0];
        el.lastChild = el.childNodes[el.childNodes.length - 1];
        return newNode;
    };
    el.removeAttribute = function(name) { if (el._attrs) delete el._attrs[name]; };
    el.getAttribute = function(name) { return el._attrs ? (el._attrs[name] !== undefined ? el._attrs[name] : null) : null; };
    el.setAttribute = function(name, val) { if (el._attrs) el._attrs[name] = String(val); if (name === 'id') el.id = String(val); if (name === 'class') el.className = String(val); };
    el.hasAttribute = function(name) { return el._attrs && el._attrs[name] !== undefined; };
    el.setAttributeNS = function(nsUri, name, val) { el.setAttribute(name, val); };
    el.getAttributeNS = function(nsUri, name) { return el.getAttribute(name); };
    el.removeAttributeNS = function(nsUri, name) { el.removeAttribute(name); };
    el.getBoundingClientRect = function() { return {x:0,y:0,width:0,height:0,top:0,right:0,bottom:0,left:0}; };
    el.getBBox = function() { return {x:0,y:0,width:80,height:80}; };
    el.normalize = function() {};
    el.contains = function(other) { return false; };
    el.matches = function(sel) { return false; };
    el.closest = function(sel) { return null; };
    el.addEventListener = function() {};
    el.removeEventListener = function() {};
    el.dispatchEvent = function() {};
    el.style = createStyleObject();
    el.cloneNode = function(deep) {
        var clone = _createParseNode(tagName, ns, ownerDoc);
        if (el._attrs) {
            var keys = Object.keys(el._attrs);
            for (var k = 0; k < keys.length; k++) clone._attrs[keys[k]] = el._attrs[keys[k]];
        }
        clone.id = el.id;
        clone.className = el.className;
        if (deep && el.childNodes) {
            for (var c = 0; c < el.childNodes.length; c++) {
                var childClone = el.childNodes[c].cloneNode ? el.childNodes[c].cloneNode(deep) : el.childNodes[c];
                clone.appendChild(childClone);
            }
        }
        return clone;
    };
    el.getElementsByTagName = function(name) {
        var results = [];
        function walk(node) {
            var ch = node.childNodes || [];
            for (var i = 0; i < ch.length; i++) {
                if (ch[i].nodeType === 1 && (name === '*' || (ch[i].tagName || '').toLowerCase() === name.toLowerCase())) {
                    results.push(ch[i]);
                }
                if (ch[i].childNodes) walk(ch[i]);
            }
        }
        walk(el);
        return results;
    };
    el.querySelector = function(sel) { return _querySelector(el, sel); };
    el.querySelectorAll = function(sel) { return _querySelectorAll(el, sel); };
    // firstElementChild getter
    Object.defineProperty(el, 'firstElementChild', {
        get: function() {
            for (var i = 0; i < el.children.length; i++) {
                if (el.children[i].nodeType === 1) return el.children[i];
            }
            return null;
        }, configurable: true
    });
    // innerHTML getter/setter
    Object.defineProperty(el, 'innerHTML', {
        get: function() {
            if (el.childNodes && el.childNodes.length > 0) {
                var result = '';
                for (var i = 0; i < el.childNodes.length; i++) {
                    var cn = el.childNodes[i];
                    if (cn.nodeType === 3) result += cn.textContent || '';
                    else if (cn.nodeType === 1) result += (cn.outerHTML || '');
                }
                return result;
            }
            return '';
        },
        set: function(val) {
            el.childNodes = [];
            el.children = [];
            el.firstChild = null;
            el.lastChild = null;
            if (val) {
                var parsed = _parseHTML(val, ownerDoc, el.namespaceURI);
                for (var i = 0; i < parsed.length; i++) {
                    el.appendChild(parsed[i]);
                }
            }
        },
        configurable: true
    });
    // outerHTML getter
    Object.defineProperty(el, 'outerHTML', {
        get: function() {
            var tag = (el.namespaceURI === SVG_NS) ? _svgTagName(el.tagName) : (el.tagName || 'div').toLowerCase();
            var s = '<' + tag;
            if (el._attrs) {
                var keys = Object.keys(el._attrs);
                for (var i = 0; i < keys.length; i++) {
                    s += ' ' + keys[i] + '="' + _escapeXmlAttr(el._attrs[keys[i]]) + '"';
                }
            }
            var inner = el.innerHTML;
            if (inner) { s += '>' + inner + '</' + tag + '>'; }
            else { s += '/>'; }
            return s;
        },
        configurable: true
    });
    return el;
}

// ── DOM Element factory ─────────────────────────────────────────────────────
function createDomElement(tagName, namespaceURI) {
    var el = EventTargetMixin({});
    el.nodeType = 1;
    // HTML elements use uppercase tagName (browser convention);
    // SVG elements preserve the original case (SVG is case-sensitive)
    if (namespaceURI === SVG_NS) {
        var upper = tagName ? tagName.toUpperCase() : 'SVG';
        el.tagName = SVG_CAMEL_CASE_TAGS[upper] || (tagName || 'svg');
    } else {
        el.tagName = tagName ? tagName.toUpperCase() : 'DIV';
    }
    el.nodeName = el.tagName;
    el.namespaceURI = namespaceURI || XHTML_NS;
    el.ownerDocument = null; // will be set to document after document is created
    el.className = '';
    el.id = '';
    el._innerHTMLRaw = '';
    el.style = createStyleObject();
    el.childNodes = [];
    el.children = [];
    el.parentNode = null;

    // parentElement — returns parentNode if it's an Element (nodeType 1), else null
    // Mermaid's Gantt chart (D3) accesses el.parentElement.offsetWidth for layout
    Object.defineProperty(el, 'parentElement', {
        get: function() {
            return (el.parentNode && el.parentNode.nodeType === 1) ? el.parentNode : null;
        },
        configurable: true
    });
    el.firstChild = null;
    el.lastChild = null;
    el.nextSibling = null;
    el.previousSibling = null;
    el.attributes = [];

    // classList API — required by Mermaid's sequence diagram renderer
    // for loop/alt/note/activation box rendering
    el.classList = {
        _el: el,
        add: function() {
            var classes = (el.className || '').split(/\s+/).filter(function(c) { return c; });
            for (var i = 0; i < arguments.length; i++) {
                var cls = arguments[i];
                if (cls && classes.indexOf(cls) < 0) classes.push(cls);
            }
            el.className = classes.join(' ');
            if (el._attrs) el._attrs['class'] = el.className;
        },
        remove: function() {
            var classes = (el.className || '').split(/\s+/).filter(function(c) { return c; });
            for (var i = 0; i < arguments.length; i++) {
                var cls = arguments[i];
                var idx = classes.indexOf(cls);
                if (idx >= 0) classes.splice(idx, 1);
            }
            el.className = classes.join(' ');
            if (el._attrs) el._attrs['class'] = el.className;
        },
        contains: function(cls) {
            return (' ' + (el.className || '') + ' ').indexOf(' ' + cls + ' ') >= 0;
        },
        toggle: function(cls, force) {
            if (force !== undefined) {
                if (force) { el.classList.add(cls); return true; }
                else { el.classList.remove(cls); return false; }
            }
            if (el.classList.contains(cls)) { el.classList.remove(cls); return false; }
            el.classList.add(cls); return true;
        },
        replace: function(oldCls, newCls) {
            if (!el.classList.contains(oldCls)) return false;
            el.classList.remove(oldCls);
            el.classList.add(newCls);
            return true;
        },
        item: function(index) {
            var classes = (el.className || '').split(/\s+/).filter(function(c) { return c; });
            return index < classes.length ? classes[index] : null;
        },
        toString: function() { return el.className || ''; }
    };
    Object.defineProperty(el.classList, 'length', {
        get: function() {
            return (el.className || '').split(/\s+/).filter(function(c) { return c; }).length;
        },
        configurable: true
    });
    el._attrs = {};

    // Make innerHTML a dynamic getter/setter so DOM-built children are serialized
    Object.defineProperty(el, 'innerHTML', {
        get: function() {
            if (el.childNodes && el.childNodes.length > 0) {
                var result = '';
                for (var i = 0; i < el.childNodes.length; i++) {
                    result += _serializeNode(el.childNodes[i], el.namespaceURI);
                }
                return result;
            }
            return el._innerHTMLRaw || '';
        },
        set: function(val) {
            el._innerHTMLRaw = val;
            // Clear existing children
            el.childNodes = [];
            el.children = [];
            el.firstChild = null;
            el.lastChild = null;
            // Parse HTML into DOM nodes so DOMPurify can iterate them
            if (val && typeof val === 'string' && val.indexOf('<') >= 0) {
                try {
                    var parsed = _parseHTML(val, el.ownerDocument || document, el.namespaceURI);
                    for (var pi = 0; pi < parsed.length; pi++) {
                        var pn = parsed[pi];
                        pn.parentNode = el;
                        el.childNodes.push(pn);
                        if (pn.nodeType === 1) el.children.push(pn);
                    }
                    if (el.childNodes.length > 0) {
                        el.firstChild = el.childNodes[0];
                        el.lastChild = el.childNodes[el.childNodes.length - 1];
                    }
                } catch(e) {
                    // Parsing failed, keep raw string as fallback
                }
            }
        },
        configurable: true,
        enumerable: true
    });

    // outerHTML getter
    Object.defineProperty(el, 'outerHTML', {
        get: function() {
            return _serializeNode(el, (el.parentNode ? el.parentNode.namespaceURI : null) || XHTML_NS);
        },
        configurable: true,
        enumerable: true
    });

    // textContent: getter returns text from all descendants, setter clears children
    Object.defineProperty(el, 'textContent', {
        get: function() {
            if (el.childNodes && el.childNodes.length > 0) {
                var result = '';
                for (var i = 0; i < el.childNodes.length; i++) {
                    var child = el.childNodes[i];
                    if (child.nodeType === 3) result += child.textContent || '';
                    else if (child.textContent) result += child.textContent;
                }
                return result;
            }
            return el._textContent || '';
        },
        set: function(val) {
            el._textContent = val;
            el.childNodes = [];
            el.children = [];
            // Create a text node child so serialization picks up the content
            if (val != null && val !== '') {
                var textNode = { nodeType: 3, textContent: String(val), ownerDocument: el.ownerDocument };
                el.childNodes.push(textNode);
                el.firstChild = textNode;
                el.lastChild = textNode;
            } else {
                el.firstChild = null;
                el.lastChild = null;
            }
        },
        configurable: true,
        enumerable: true
    });

    el.setAttribute = function(key, value) {
        el._attrs[key] = String(value);
        if (key === 'class') el.className = String(value);
        if (key === 'id') el.id = String(value);
        // Sync data-* attributes to dataset
        if (key.indexOf('data-') === 0) {
            var camel = key.substring(5).replace(/-([a-z])/g, function(m, c) { return c.toUpperCase(); });
            el.dataset[camel] = String(value);
        }
    };
    el.getAttribute = function(key) {
        if (key === 'class') return el.className || null;
        return el._attrs[key] !== undefined ? el._attrs[key] : null;
    };
    el.removeAttribute = function(key) {
        delete el._attrs[key];
    };
    el.hasAttribute = function(key) {
        return el._attrs[key] !== undefined;
    };
    el.getAttributeNS = function(ns, key) { return el.getAttribute(key); };
    el.setAttributeNS = function(ns, key, value) { el.setAttribute(key, value); };
    el.removeAttributeNS = function(ns, key) { el.removeAttribute(key); };

    // Helper: maintain previousSibling / nextSibling for all childNodes
    function _updateSiblings(parent) {
        var cn = parent.childNodes;
        for (var si = 0; si < cn.length; si++) {
            cn[si].previousSibling = si > 0 ? cn[si - 1] : null;
            cn[si].nextSibling = si < cn.length - 1 ? cn[si + 1] : null;
        }
        parent.firstChild = cn.length > 0 ? cn[0] : null;
        parent.lastChild = cn.length > 0 ? cn[cn.length - 1] : null;
    }

    el.appendChild = function(child) {
        if (child.parentNode && child.parentNode.removeChild) {
            child.parentNode.removeChild(child);
        }
        el.childNodes.push(child);
        if (child.nodeType === 1) el.children.push(child);
        child.parentNode = el;
        _updateSiblings(el);
        return child;
    };
    el.removeChild = function(child) {
        var idx = el.childNodes.indexOf(child);
        if (idx >= 0) el.childNodes.splice(idx, 1);
        var cidx = el.children.indexOf(child);
        if (cidx >= 0) el.children.splice(cidx, 1);
        child.parentNode = null;
        child.previousSibling = null;
        child.nextSibling = null;
        _updateSiblings(el);
        return child;
    };
    el.insertBefore = function(newNode, refNode) {
        if (!refNode) return el.appendChild(newNode);
        if (newNode.parentNode && newNode.parentNode.removeChild) {
            newNode.parentNode.removeChild(newNode);
        }
        var idx = el.childNodes.indexOf(refNode);
        if (idx >= 0) {
            el.childNodes.splice(idx, 0, newNode);
            if (newNode.nodeType === 1) {
                var cidx = el.children.indexOf(refNode);
                if (cidx >= 0) el.children.splice(cidx, 0, newNode);
                else el.children.push(newNode);
            }
        } else {
            el.childNodes.push(newNode);
            if (newNode.nodeType === 1) el.children.push(newNode);
        }
        newNode.parentNode = el;
        _updateSiblings(el);
        return newNode;
    };
    el.replaceChild = function(newChild, oldChild) {
        var idx = el.childNodes.indexOf(oldChild);
        if (idx >= 0) el.childNodes[idx] = newChild;
        newChild.parentNode = el;
        oldChild.parentNode = null;
        return oldChild;
    };
    el.contains = function(other) {
        if (other === el) return true;
        for (var i = 0; i < el.childNodes.length; i++) {
            if (el.childNodes[i] === other) return true;
            if (el.childNodes[i].contains && el.childNodes[i].contains(other)) return true;
        }
        return false;
    };

    // compareDocumentPosition — DOM Level 3 method used by D3 for selection ordering
    // (required by Sankey, force-directed layouts, etc.)
    // Returns bitmask: 1=DISCONNECTED, 2=PRECEDING, 4=FOLLOWING,
    //                  8=CONTAINS, 16=CONTAINED_BY, 32=IMPL_SPECIFIC
    el.compareDocumentPosition = function(other) {
        if (el === other) return 0;
        // Check if other is contained by el
        if (el.contains(other)) return 16 + 4; // CONTAINED_BY | FOLLOWING
        // Check if el is contained by other
        if (other && other.contains && other.contains(el)) return 8 + 2; // CONTAINS | PRECEDING
        // Walk up to find common ancestor and determine order
        function _ancestors(node) {
            var a = [];
            while (node) { a.unshift(node); node = node.parentNode; }
            return a;
        }
        var aPath = _ancestors(el);
        var bPath = _ancestors(other);
        // Find common ancestor
        var i = 0;
        while (i < aPath.length && i < bPath.length && aPath[i] === bPath[i]) i++;
        if (i === 0) return 1; // DISCONNECTED
        var parent = aPath[i - 1];
        var aChild = aPath[i];
        var bChild = bPath[i];
        // Compare sibling order
        if (parent && parent.childNodes) {
            var aIdx = parent.childNodes.indexOf(aChild);
            var bIdx = parent.childNodes.indexOf(bChild);
            if (aIdx < bIdx) return 4; // FOLLOWING
            if (aIdx > bIdx) return 2; // PRECEDING
        }
        return 1; // DISCONNECTED (fallback)
    };

    // insertAdjacentElement — used by Mermaid for sequence diagram elements
    el.insertAdjacentElement = function(position, newElement) {
        switch ((position || '').toLowerCase()) {
            case 'beforebegin':
                if (el.parentNode) el.parentNode.insertBefore(newElement, el);
                break;
            case 'afterbegin':
                el.insertBefore(newElement, el.firstChild);
                break;
            case 'beforeend':
                el.appendChild(newElement);
                break;
            case 'afterend':
                if (el.parentNode) {
                    if (el.nextSibling) el.parentNode.insertBefore(newElement, el.nextSibling);
                    else el.parentNode.appendChild(newElement);
                }
                break;
        }
        return newElement;
    };

    // insertAdjacentHTML — stub that creates a text node
    el.insertAdjacentHTML = function(position, html) {
        // Simple stub: create element from HTML text (limited support)
        var temp = createDomElement('span');
        temp._innerHTMLRaw = html;
        el.insertAdjacentElement(position, temp);
    };

    // dataset — simple object that maps to data-* attributes
    // Cannot use Proxy (may not be available), so provide basic get/set helpers
    el.dataset = {};
    el._syncDataset = function() {
        // Sync data-* attrs to dataset object
        if (!el._attrs) return;
        var keys = Object.keys(el._attrs);
        for (var di = 0; di < keys.length; di++) {
            if (keys[di].indexOf('data-') === 0) {
                var camel = keys[di].substring(5).replace(/-([a-z])/g, function(m, c) { return c.toUpperCase(); });
                el.dataset[camel] = el._attrs[keys[di]];
            }
        }
    };

    // getRootNode — walk up to the topmost parent (needed by Mermaid mindmap)
    el.getRootNode = function(opts) {
        var current = el;
        while (current.parentNode) current = current.parentNode;
        return current;
    };

    // isConnected — check if element is in a document tree
    Object.defineProperty(el, 'isConnected', {
        get: function() {
            var root = el.getRootNode();
            return root === document || root === document.documentElement || (root.nodeType === 9);
        },
        configurable: true
    });

    el.cloneNode = function(deep) {
        var clone = createDomElement(tagName, namespaceURI);
        clone.className = el.className;
        clone.id = el.id;
        var keys = Object.keys(el._attrs);
        for (var i = 0; i < keys.length; i++) {
            clone._attrs[keys[i]] = el._attrs[keys[i]];
        }
        // Copy style properties
        if (el.style && el.style._props) {
            var skeys = Object.keys(el.style._props);
            for (var s = 0; s < skeys.length; s++) {
                clone.style._props[skeys[s]] = el.style._props[skeys[s]];
                var camel = skeys[s].replace(/-([a-z])/g, function(m, c) { return c.toUpperCase(); });
                clone.style[camel] = el.style._props[skeys[s]];
            }
        }
        if (deep) {
            // Deep clone: recursively clone child nodes
            if (el.childNodes && el.childNodes.length > 0) {
                for (var j = 0; j < el.childNodes.length; j++) {
                    var child = el.childNodes[j];
                    if (child.cloneNode) {
                        clone.appendChild(child.cloneNode(true));
                    } else if (child.nodeType === 3) {
                        // Text node
                        clone.appendChild({ nodeType: 3, textContent: child.textContent, ownerDocument: el.ownerDocument });
                    }
                }
            } else if (el._innerHTMLRaw) {
                // No child nodes but has raw HTML — copy it
                clone._innerHTMLRaw = el._innerHTMLRaw;
            }
        } else {
            // Shallow clone: only copy _innerHTMLRaw if no children
            if (!el.childNodes || el.childNodes.length === 0) {
                clone._innerHTMLRaw = el._innerHTMLRaw || '';
            }
        }
        return clone;
    };

    el.querySelector = function(sel) { return _querySelector(el, sel); };
    el.querySelectorAll = function(sel) { return _querySelectorAll(el, sel); };
    el.getElementsByTagName = function(name) { return _querySelectorAll(el, name); };
    el.getElementsByClassName = function(name) { return _querySelectorAll(el, '.' + name); };
    el.matches = function(sel) { return _matchesSelector(el, sel); };
    el.closest = function(sel) {
        var current = el;
        while (current) {
            if (current.nodeType === 1 && _matchesSelector(current, sel)) return current;
            current = current.parentNode;
        }
        return null;
    };
    el.getBoundingClientRect = function() {
        var dims = _computeElementDims(el);
        return { x: dims.x, y: dims.y, width: dims.w, height: dims.h,
                 top: dims.y, right: dims.x + dims.w, bottom: dims.y + dims.h, left: dims.x };
    };
    el.getComputedTextLength = function() {
        return _estimateTextWidth(el);
    };
    el.getBBox = function() {
        var _tag = (el.tagName || '').toLowerCase();
        // SVG root elements: return actual content bounds, not declared dimensions.
        // _computeElementDims returns the container size (for offsetWidth/offsetHeight)
        // but getBBox must return the aggregate of all child content.
        var dims = (_tag === 'svg') ? _computeSvgContentBBox(el) : _computeElementDims(el);
        return { x: dims.x, y: dims.y, width: dims.w, height: dims.h };
    };
    el.getTotalLength = function() { return 0; };
    el.getPointAtLength = function(len) { return { x: 0, y: 0 }; };

    // SVG-specific
    el.createSVGPoint = function() { return { x: 0, y: 0, matrixTransform: function() { return { x: 0, y: 0 }; } }; };
    el.getScreenCTM = function() { return { inverse: function() { return {}; } }; };

    el.focus = function() {};
    el.blur = function() {};
    el.click = function() {};
    el.hasChildNodes = function() { return el.childNodes && el.childNodes.length > 0; };
    el.normalize = function() {};

    // toJSON — prevents "Converting circular structure to JSON" errors when
    // Mermaid block/architecture diagrams pass objects containing DOM elements
    // to JSON.stringify (e.g. for internal state serialization).
    el.toJSON = function() {
        return { tagName: el.tagName, id: el.id, className: el.className,
                 childCount: el.childNodes ? el.childNodes.length : 0 };
    };

    el.remove = function() {
        if (el.parentNode && el.parentNode.removeChild) {
            el.parentNode.removeChild(el);
        }
    };

    // Layout dimension properties (used by Gantt charts and other diagram types)
    Object.defineProperty(el, 'offsetWidth', {
        get: function() { return _computeElementDims(el).w; },
        configurable: true
    });
    Object.defineProperty(el, 'offsetHeight', {
        get: function() { return _computeElementDims(el).h; },
        configurable: true
    });
    Object.defineProperty(el, 'clientWidth', {
        get: function() { return _computeElementDims(el).w; },
        configurable: true
    });
    Object.defineProperty(el, 'clientHeight', {
        get: function() { return _computeElementDims(el).h; },
        configurable: true
    });
    Object.defineProperty(el, 'scrollWidth', {
        get: function() { return _computeElementDims(el).w; },
        configurable: true
    });
    Object.defineProperty(el, 'scrollHeight', {
        get: function() { return _computeElementDims(el).h; },
        configurable: true
    });

    // Canvas 2D context stub (used by Mermaid mindmap for text measurement)
    el.getContext = function(type) {
        if (type === '2d') {
            var ctx = {
                _font: '16px sans-serif',
                measureText: function(text) {
                    // Use Java bridge for accurate measurement
                    var w, asc = 10, desc = 3;
                    if (typeof javaBridge !== 'undefined' && javaBridge.measureTextFull) {
                        try {
                            // Parse font string: "16px sans-serif" or "bold 14px arial"
                            var fontSize = 16;
                            var fontFamily = 'sans-serif';
                            var fontParts = (ctx._font || '').match(/([\d.]+)px\s+(.*)/);
                            if (fontParts) {
                                fontSize = parseFloat(fontParts[1]) || 16;
                                fontFamily = fontParts[2] || 'sans-serif';
                            }
                            var result = javaBridge.measureTextFull(text || '', fontFamily, fontSize);
                            var parts = ('' + result).split(',');
                            w = parseFloat(parts[0]) || 0;
                            asc = parseFloat(parts[1]) || 10;
                            desc = parseFloat(parts[2]) || 3;
                        } catch (e) {
                            w = (text ? text.length : 0) * 7;
                        }
                    } else {
                        w = (text ? text.length : 0) * 7;
                    }
                    return {
                        width: w,
                        actualBoundingBoxAscent: asc,
                        actualBoundingBoxDescent: desc,
                        fontBoundingBoxAscent: asc + 2,
                        fontBoundingBoxDescent: desc + 1
                    };
                },
                fillStyle: '#000',
                strokeStyle: '#000',
                lineWidth: 1,
                fillRect: function() {},
                clearRect: function() {},
                strokeRect: function() {},
                beginPath: function() {},
                closePath: function() {},
                moveTo: function() {},
                lineTo: function() {},
                arc: function() {},
                fill: function() {},
                stroke: function() {},
                fillText: function() {},
                strokeText: function() {},
                save: function() {},
                restore: function() {},
                translate: function() {},
                rotate: function() {},
                scale: function() {},
                setTransform: function() {},
                getTransform: function() { return { a:1,b:0,c:0,d:1,e:0,f:0 }; },
                createLinearGradient: function() { return { addColorStop: function() {} }; },
                createRadialGradient: function() { return { addColorStop: function() {} }; },
                drawImage: function() {},
                getImageData: function() { return { data: [] }; },
                putImageData: function() {}
            };
            Object.defineProperty(ctx, 'font', {
                get: function() { return ctx._font; },
                set: function(val) { ctx._font = val; },
                configurable: true, enumerable: true
            });
            return ctx;
        }
        return null;
    };

    return el;
}

// ── DOM constructor stubs (required by DOMPurify embedded in Mermaid) ───────
function Element() {}
Element.prototype = {
    nodeType: 1,
    setAttribute: function(k, v) {},
    getAttribute: function(k) { return null; },
    removeAttribute: function(k) {},
    hasAttribute: function(k) { return false; },
    setAttributeNS: function(ns, k, v) {},
    getAttributeNS: function(ns, k) { return null; },
    removeAttributeNS: function(ns, k) {},
    appendChild: function(c) { return c; },
    removeChild: function(c) { return c; },
    insertBefore: function(n, r) { return n; },
    replaceChild: function(n, o) { return o; },
    cloneNode: function(deep) { return createDomElement('div'); },
    contains: function(other) { return false; },
    hasChildNodes: function() { return this.childNodes && this.childNodes.length > 0; },
    normalize: function() {},
    querySelector: function(sel) { return null; },
    querySelectorAll: function(sel) { return []; },
    getElementsByTagName: function(name) { return []; },
    getElementsByClassName: function(name) { return []; },
    matches: function(sel) { return false; },
    closest: function(sel) { return null; },
    addEventListener: function() {},
    removeEventListener: function() {},
    dispatchEvent: function() {},
    getBoundingClientRect: function() { return {x:0,y:0,width:0,height:0,top:0,right:0,bottom:0,left:0}; },
    style: {},
    className: '',
    id: '',
    namespaceURI: 'http://www.w3.org/1999/xhtml'
};
// DOMPurify's V2() uses Object.getOwnPropertyDescriptor to find getters on Element.prototype.
// It uses these safe references to access DOM node properties.
// We must define childNodes, parentNode, nextSibling, nodeName, textContent etc. as GETTERS
// so V2 can find and call them on actual nodes.
Object.defineProperties(Element.prototype, {
    childNodes: { get: function() { return this._childNodes || []; }, configurable: true, enumerable: true },
    children: { get: function() { return this._children || []; }, configurable: true, enumerable: true },
    parentNode: { get: function() { return this._parentNode || null; }, configurable: true, enumerable: true },
    firstChild: { get: function() { var cn = this._childNodes || this.childNodes || []; return cn.length > 0 ? cn[0] : null; }, configurable: true, enumerable: true },
    lastChild: { get: function() { var cn = this._childNodes || this.childNodes || []; return cn.length > 0 ? cn[cn.length - 1] : null; }, configurable: true, enumerable: true },
    nextSibling: { get: function() { return this._nextSibling || null; }, configurable: true, enumerable: true },
    previousSibling: { get: function() { return this._previousSibling || null; }, configurable: true, enumerable: true },
    textContent: { get: function() { return this._textContent || ''; }, set: function(v) { this._textContent = v; }, configurable: true, enumerable: true },
    innerHTML: { get: function() { return this._innerHTML || ''; }, set: function(v) { this._innerHTML = v; }, configurable: true, enumerable: true },
    outerHTML: { get: function() { return this._outerHTML || ''; }, configurable: true, enumerable: true },
    nodeName: { get: function() { return this._nodeName || this.tagName || 'DIV'; }, configurable: true, enumerable: true },
    tagName: { get: function() { return this._tagName || 'DIV'; }, configurable: true, enumerable: true },
    firstElementChild: { get: function() {
        var ch = this._children || this.children || [];
        for (var i = 0; i < ch.length; i++) { if (ch[i] && ch[i].nodeType === 1) return ch[i]; }
        return null;
    }, configurable: true, enumerable: true },
    attributes: { get: function() { return this._attributes || []; }, configurable: true, enumerable: true }
});

function Node() {}
Node.prototype = Element.prototype;
Node.ELEMENT_NODE = 1;
Node.TEXT_NODE = 3;
Node.COMMENT_NODE = 8;
Node.DOCUMENT_NODE = 9;
Node.DOCUMENT_FRAGMENT_NODE = 11;

function DocumentFragment() {}
DocumentFragment.prototype = Element.prototype;

function HTMLTemplateElement() {}
HTMLTemplateElement.prototype = Element.prototype;
HTMLTemplateElement.prototype.content = { ownerDocument: null, childNodes: [], firstChild: null };

function HTMLFormElement() {}
HTMLFormElement.prototype = Element.prototype;

function HTMLElement() {}
HTMLElement.prototype = Element.prototype;

function SVGElement() {}
SVGElement.prototype = Element.prototype;

function Text() {}
Text.prototype = { nodeType: 3, nodeName: '#text', cloneNode: function() { return { nodeType: 3, nodeName: '#text', textContent: this.textContent }; } };
Object.defineProperty(Text.prototype, 'textContent', { get: function() { return this._textContent || ''; }, set: function(v) { this._textContent = v; }, configurable: true });

function Comment() {}
Comment.prototype = { nodeType: 8, nodeName: '#comment' };
Object.defineProperty(Comment.prototype, 'textContent', { get: function() { return this._textContent || ''; }, set: function(v) { this._textContent = v; }, configurable: true });

function NodeFilter() {}
NodeFilter.SHOW_ALL = 0xFFFFFFFF;
NodeFilter.SHOW_ELEMENT = 1;
NodeFilter.SHOW_TEXT = 4;
NodeFilter.SHOW_COMMENT = 128;
NodeFilter.FILTER_ACCEPT = 1;
NodeFilter.FILTER_REJECT = 2;
NodeFilter.FILTER_SKIP = 3;

function NamedNodeMap() {}
NamedNodeMap.prototype = {
    length: 0,
    getNamedItem: function(name) { return null; },
    setNamedItem: function(item) {},
    removeNamedItem: function(name) {},
    item: function(index) { return null; }
};

// ── window ──────────────────────────────────────────────────────────────────
var window = EventTargetMixin({});
var self = window;
var globalThis = window;

window.window = window;
window.self = window;
window.top = window;
window.parent = window;

window.location = {
    href: 'about:blank',
    protocol: 'https:',
    host: 'localhost',
    hostname: 'localhost',
    pathname: '/',
    search: '',
    hash: '',
    origin: 'https://localhost'
};

window.navigator = { userAgent: 'GraalJS MermaidSpike/1.0', platform: 'Java', language: 'en' };
var navigator = window.navigator;

// DOM constructors on window (required by DOMPurify)
window.Element = Element;
window.Node = Node;
window.DocumentFragment = DocumentFragment;
window.HTMLTemplateElement = HTMLTemplateElement;
window.HTMLFormElement = HTMLFormElement;
window.HTMLElement = HTMLElement;
window.SVGElement = SVGElement;
window.Text = Text;
window.Comment = Comment;
window.NodeFilter = NodeFilter;
window.NamedNodeMap = NamedNodeMap;

window.screen = { width: 1920, height: 1080, availWidth: 1920, availHeight: 1080, colorDepth: 24 };
var screen = window.screen;

window.devicePixelRatio = 1;
window.innerWidth = 1920;
window.innerHeight = 1080;
window.outerWidth = 1920;
window.outerHeight = 1080;
window.pageXOffset = 0;
window.pageYOffset = 0;
window.scrollX = 0;
window.scrollY = 0;

window.getComputedStyle = function(el) {
    // Build defaults — use 'relative' position so Cytoscape accepts the container
    var defaults = {
        'display': 'block', 'visibility': 'visible', 'opacity': '1',
        'font-size': '16px', 'font-family': 'sans-serif', 'font-weight': '400', 'font-style': 'normal',
        'line-height': '1.2', 'color': 'rgb(0, 0, 0)', 'background-color': 'rgba(0, 0, 0, 0)',
        'fill': 'rgb(0, 0, 0)', 'stroke': 'none', 'stroke-width': '1',
        'width': '1600px', 'height': '900px',
        'margin': '0px', 'margin-top': '0px', 'margin-right': '0px', 'margin-bottom': '0px', 'margin-left': '0px',
        'padding': '0px', 'padding-top': '0px', 'padding-right': '0px', 'padding-bottom': '0px', 'padding-left': '0px',
        'border': '0px none rgb(0, 0, 0)', 'border-width': '0px',
        'border-top-width': '0px', 'border-right-width': '0px', 'border-bottom-width': '0px', 'border-left-width': '0px',
        'position': 'relative', 'overflow': 'visible',
        'white-space': 'normal', 'text-align': 'start', 'text-decoration': 'none',
        'transform': 'none', 'transition': 'none',
        'box-sizing': 'content-box',
        'min-width': '0px', 'min-height': '0px',
        'max-width': 'none', 'max-height': 'none',
        'top': 'auto', 'right': 'auto', 'bottom': 'auto', 'left': 'auto',
        'float': 'none', 'clear': 'none',
        'vertical-align': 'baseline',
        'outline': 'none', 'cursor': 'auto',
        'pointer-events': 'auto',
        'user-select': 'auto',
        'clip': 'auto',
        'border-radius': '0px'
    };

    // Resolve a value: element's own style overrides defaults
    function resolve(prop) {
        // Check element's inline style first (kebab-case) via style object
        if (el && el.style && el.style._props && el.style._props[prop] !== undefined && el.style._props[prop] !== '') {
            return el.style._props[prop];
        }
        // Also check the raw style attribute string (set via setAttribute('style', ...))
        if (el && el._attrs && el._attrs['style']) {
            var styleStr = el._attrs['style'];
            var re = new RegExp('(?:^|;)\\s*' + prop.replace(/[-\/\\^$*+?.()|[\]{}]/g, '\\$&') + '\\s*:\\s*([^;]+)', 'i');
            var match = styleStr.match(re);
            if (match) return match[1].trim();
        }
        // Check element's attributes (width/height on SVG/canvas elements)
        if (el && el._attrs) {
            if (prop === 'width' && el._attrs['width']) return el._attrs['width'] + (String(el._attrs['width']).indexOf('px') < 0 ? 'px' : '');
            if (prop === 'height' && el._attrs['height']) return el._attrs['height'] + (String(el._attrs['height']).indexOf('px') < 0 ? 'px' : '');
        }
        return defaults[prop] || '';
    }

    // Build the result object with ALL default properties accessible directly
    // Both camelCase and kebab-case access must work (Cytoscape uses camelCase)
    var result = {
        getPropertyValue: function(prop) {
            // Convert camelCase to kebab-case
            var kebab = prop.replace(/([A-Z])/g, '-$1').toLowerCase();
            return resolve(kebab) || resolve(prop) || '';
        },
        setProperty: function() {},
        removeProperty: function() { return ''; },
        length: 0,
        item: function() { return ''; }
    };

    // Populate direct property access for all defaults (both kebab and camelCase)
    var keys = Object.keys(defaults);
    for (var i = 0; i < keys.length; i++) {
        var kebab = keys[i];
        var val = resolve(kebab);
        // kebab-case access
        result[kebab] = val;
        // camelCase access
        var camel = kebab.replace(/-([a-z])/g, function(m, c) { return c.toUpperCase(); });
        result[camel] = val;
    }

    return result;
};

window.matchMedia = function(query) {
    return {
        matches: false,
        media: query,
        addEventListener: function() {},
        removeEventListener: function() {},
        addListener: function() {},
        removeListener: function() {}
    };
};

window.getSelection = function() {
    return {
        removeAllRanges: function() {},
        addRange: function() {},
        getRangeAt: function() { return { startContainer: null, endContainer: null }; },
        rangeCount: 0
    };
};

// requestAnimationFrame: Do NOT invoke callback synchronously.
// Cytoscape starts an animation loop that calls requestAnimationFrame recursively.
// Executing callbacks synchronously would cause infinite recursion / stack overflow.
// Headless mode does not need animation — the layout runs synchronously in run().
var __rafId = 0;
var __rafQueue = [];
window.requestAnimationFrame = function(fn) {
    __rafId++;
    __rafQueue.push({id: __rafId, fn: fn});
    return __rafId;
};
window.cancelAnimationFrame = function(id) {
    __rafQueue = __rafQueue.filter(function(item) { return item.id !== id; });
};
// Flush up to N queued animation frames (used after layout completes)
window.__flushAnimationFrames = function(maxIterations) {
    var count = 0;
    while (__rafQueue.length > 0 && count < (maxIterations || 10)) {
        var item = __rafQueue.shift();
        try { item.fn(Date.now()); } catch(e) {}
        count++;
    }
};
window.setTimeout = function(fn, delay) { if (typeof fn === 'function') fn(); return 0; };
window.clearTimeout = function(id) {};
window.setInterval = function(fn, delay) { return 0; };
window.clearInterval = function(id) {};

// btoa / atob — real Base64 encoding/decoding (Mermaid 11 uses btoa as global)
var _b64chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
var btoa = function(str) {
    str = String(str);
    var out = '';
    for (var i = 0; i < str.length; i += 3) {
        var b1 = str.charCodeAt(i) & 0xFF;
        var b2 = i + 1 < str.length ? str.charCodeAt(i + 1) & 0xFF : 0;
        var b3 = i + 2 < str.length ? str.charCodeAt(i + 2) & 0xFF : 0;
        out += _b64chars.charAt(b1 >> 2);
        out += _b64chars.charAt(((b1 & 3) << 4) | (b2 >> 4));
        out += i + 1 < str.length ? _b64chars.charAt(((b2 & 15) << 2) | (b3 >> 6)) : '=';
        out += i + 2 < str.length ? _b64chars.charAt(b3 & 63) : '=';
    }
    return out;
};
var atob = function(str) {
    str = String(str).replace(/=+$/, '');
    var out = '';
    for (var i = 0; i < str.length; i += 4) {
        var b1 = _b64chars.indexOf(str.charAt(i));
        var b2 = _b64chars.indexOf(str.charAt(i + 1));
        var b3 = _b64chars.indexOf(str.charAt(i + 2));
        var b4 = _b64chars.indexOf(str.charAt(i + 3));
        out += String.fromCharCode((b1 << 2) | (b2 >> 4));
        if (b3 >= 0) out += String.fromCharCode(((b2 & 15) << 4) | (b3 >> 2));
        if (b4 >= 0) out += String.fromCharCode(((b3 & 3) << 6) | b4);
    }
    return out;
};
window.btoa = btoa;
window.atob = atob;

// Make timing functions available globally
var setTimeout = window.setTimeout;
var clearTimeout = window.clearTimeout;
var setInterval = window.setInterval;
var clearInterval = window.clearInterval;
var requestAnimationFrame = window.requestAnimationFrame;
var cancelAnimationFrame = window.cancelAnimationFrame;

// ── DOMParser ───────────────────────────────────────────────────────────────
function DOMParser() {}
DOMParser.prototype.parseFromString = function(str, type) {
    var doc = {
        documentElement: createDomElement('html'),
        querySelector: function(sel) { return null; },
        querySelectorAll: function(sel) { return []; },
        getElementsByTagName: function(name) { return []; },
        getElementById: function(id) { return null; },
        createElementNS: function(ns, name) { return createDomElement(name, ns); },
        createElement: function(name) { return createDomElement(name); },
        createTextNode: function(text) { return { nodeType: 3, nodeName: '#text', textContent: text }; }
    };
    return doc;
};
window.DOMParser = DOMParser;

// ── XMLSerializer ───────────────────────────────────────────────────────────
function XMLSerializer() {}
XMLSerializer.prototype.serializeToString = function(node) {
    if (!node) return '';
    if (node.nodeType === 3) return _escapeXmlText(node.textContent || '');
    // For SVG root, use null parent namespace so xmlns is always emitted
    var parentNs = (node.tagName && node.tagName.toLowerCase() === 'svg') ? null : XHTML_NS;
    return _serializeNode(node, parentNs);
};
window.XMLSerializer = XMLSerializer;

// ── document ────────────────────────────────────────────────────────────────
var document = EventTargetMixin({});
document.nodeType = 9;
document.documentElement = createDomElement('html');
document.documentElement.namespaceURI = 'http://www.w3.org/1999/xhtml';
document.head = createDomElement('head');
document.body = createDomElement('body');
document.documentElement.appendChild(document.head);
document.documentElement.appendChild(document.body);

// Set ownerDocument on the root elements
document.documentElement.ownerDocument = document;
document.head.ownerDocument = document;
document.body.ownerDocument = document;

// Cytoscape.js accesses getComputedStyle via node.ownerDocument.defaultView
document.defaultView = window;


document.createElement = function(name) {
    var el = createDomElement(name);
    el.ownerDocument = document;
    return el;
};
document.createElementNS = function(ns, name) {
    var el = createDomElement(name, ns);
    el.ownerDocument = document;
    return el;
};
document.createTextNode = function(text) {
    return { nodeType: 3, nodeName: '#text', textContent: text, ownerDocument: document, cloneNode: function() { return document.createTextNode(text); } };
};
document.createComment = function(data) { return { nodeType: 8, nodeName: '#comment', textContent: data, ownerDocument: document }; };
document.createDocumentFragment = function() {
    var frag = createDomElement('#fragment');
    frag.nodeType = 11;
    frag.nodeName = '#document-fragment';
    frag.ownerDocument = document;
    return frag;
};
document.createRange = function() {
    return {
        setStart: function() {},
        setEnd: function() {},
        commonAncestorContainer: document.body,
        createContextualFragment: function(html) {
            var frag = document.createDocumentFragment();
            var div = document.createElement('div');
            div.innerHTML = html;
            frag.appendChild(div);
            return frag;
        }
    };
};
document.createTreeWalker = function() {
    return { nextNode: function() { return null; } };
};

document.querySelector = function(sel) {
    if (sel === 'body') return document.body;
    if (sel === 'head') return document.head;
    if (sel === 'html') return document.documentElement;
    return _querySelector(document.documentElement, sel);
};
document.querySelectorAll = function(sel) { return _querySelectorAll(document.documentElement, sel); };
document.getElementById = function(id) { return _querySelector(document.documentElement, '#' + id); };
document.getElementsByTagName = function(name) {
    if (name === 'body') return [document.body];
    if (name === 'head') return [document.head];
    if (name === 'html') return [document.documentElement];
    return _querySelectorAll(document.documentElement, name);
};
document.getElementsByClassName = function(name) { return _querySelectorAll(document.documentElement, '.' + name); };

window.document = document;

// ── console ─────────────────────────────────────────────────────────────────
var console = {
    log:   function() { javaBridge.log(Array.prototype.slice.call(arguments).join(' ')); },
    warn:  function() { javaBridge.log('WARN: ' + Array.prototype.slice.call(arguments).join(' ')); },
    error: function() { javaBridge.log('ERROR: ' + Array.prototype.slice.call(arguments).join(' ')); },
    info:  function() { javaBridge.log('INFO: ' + Array.prototype.slice.call(arguments).join(' ')); },
    debug: function() {},
    trace: function() {},
    dir:   function() {},
    table: function() {},
    group: function() {},
    groupEnd: function() {},
    time:  function() {},
    timeEnd: function() {},
    assert: function(cond, msg) { if (!cond) javaBridge.log('ASSERT FAILED: ' + (msg || '')); }
};
window.console = console;

// ── misc browser APIs ───────────────────────────────────────────────────────
var URL = {
    createObjectURL: function(blob) { return 'blob:graaljs/' + Math.random(); },
    revokeObjectURL: function(url) {}
};
window.URL = URL;

var Blob = function(parts, options) { this.parts = parts; this.type = (options && options.type) || ''; };
window.Blob = Blob;

function XMLHttpRequest() { this._headers = {}; }
XMLHttpRequest.prototype.open = function() {};
XMLHttpRequest.prototype.send = function() {};
XMLHttpRequest.prototype.setRequestHeader = function(k, v) { this._headers[k] = v; };
XMLHttpRequest.prototype.getResponseHeader = function(k) { return null; };
XMLHttpRequest.prototype.getAllResponseHeaders = function() { return ''; };
window.XMLHttpRequest = XMLHttpRequest;

var fetch = function(url, opts) {
    return { then: function(fn) { return { catch: function(fn2) { return { finally: function(fn3) {} }; } }; } };
};
window.fetch = fetch;

// ── CustomEvent / Event ─────────────────────────────────────────────────────
function Event(type, opts) { this.type = type; this.bubbles = (opts && opts.bubbles) || false; }
function CustomEvent(type, opts) { this.type = type; this.detail = (opts && opts.detail) || null; this.bubbles = (opts && opts.bubbles) || false; }
window.Event = Event;
window.CustomEvent = CustomEvent;

// ── MutationObserver / ResizeObserver stubs ──────────────────────────────────
function MutationObserver(callback) { this._callback = callback; }
MutationObserver.prototype.observe = function() {};
MutationObserver.prototype.disconnect = function() {};
MutationObserver.prototype.takeRecords = function() { return []; };
window.MutationObserver = MutationObserver;

function ResizeObserver(callback) { this._callback = callback; }
ResizeObserver.prototype.observe = function() {};
ResizeObserver.prototype.disconnect = function() {};
ResizeObserver.prototype.unobserve = function() {};
window.ResizeObserver = ResizeObserver;

function IntersectionObserver(callback) { this._callback = callback; }
IntersectionObserver.prototype.observe = function() {};
IntersectionObserver.prototype.disconnect = function() {};
IntersectionObserver.prototype.unobserve = function() {};
window.IntersectionObserver = IntersectionObserver;

// ── performance ─────────────────────────────────────────────────────────────
window.performance = {
    now: function() { return Date.now(); },
    mark: function() {},
    measure: function() {},
    getEntriesByName: function() { return []; },
    getEntriesByType: function() { return []; }
};

// ── CSS / StyleSheet stubs ──────────────────────────────────────────────────
window.CSSStyleSheet = function() { this.cssRules = []; };
window.CSSStyleSheet.prototype.insertRule = function(rule, idx) { this.cssRules.splice(idx || 0, 0, rule); return idx || 0; };
window.CSSStyleSheet.prototype.deleteRule = function(idx) { this.cssRules.splice(idx, 1); };

// ── Standard JS built-ins on window (some libs access via window.X) ─────────
window.Error = Error;
window.TypeError = TypeError;
window.RangeError = RangeError;
window.SyntaxError = SyntaxError;
window.ReferenceError = ReferenceError;
window.EvalError = typeof EvalError !== 'undefined' ? EvalError : Error;
window.URIError = typeof URIError !== 'undefined' ? URIError : Error;
window.Object = Object;
window.Array = Array;
window.String = String;
window.Number = Number;
window.Boolean = Boolean;
window.Function = Function;
window.RegExp = RegExp;
window.Date = Date;
window.Math = Math;
window.JSON = JSON;
window.Map = typeof Map !== 'undefined' ? Map : function() {};
window.Set = typeof Set !== 'undefined' ? Set : function() {};
window.WeakMap = typeof WeakMap !== 'undefined' ? WeakMap : function() {};
window.WeakSet = typeof WeakSet !== 'undefined' ? WeakSet : function() {};
window.Promise = typeof Promise !== 'undefined' ? Promise : function(fn) { fn(function(){}, function(){}); };
window.Symbol = typeof Symbol !== 'undefined' ? Symbol : function(desc) { return desc; };
window.Proxy = typeof Proxy !== 'undefined' ? Proxy : undefined;
window.Reflect = typeof Reflect !== 'undefined' ? Reflect : undefined;
window.ArrayBuffer = typeof ArrayBuffer !== 'undefined' ? ArrayBuffer : function() {};
window.Uint8Array = typeof Uint8Array !== 'undefined' ? Uint8Array : function() {};
window.Int32Array = typeof Int32Array !== 'undefined' ? Int32Array : function() {};
window.Float64Array = typeof Float64Array !== 'undefined' ? Float64Array : function() {};
window.DataView = typeof DataView !== 'undefined' ? DataView : function() {};
// TextEncoder/TextDecoder — must also be declared as top-level vars because
// Mermaid code references them directly, not via window.
if (typeof TextEncoder === 'undefined') {
    var TextEncoder = function TextEncoder() {
        this.encoding = 'utf-8';
        this.encode = function(str) {
            if (typeof str !== 'string') str = String(str);
            var arr = [];
            for (var i = 0; i < str.length; i++) {
                var c = str.charCodeAt(i);
                if (c < 0x80) { arr.push(c); }
                else if (c < 0x800) { arr.push(0xc0 | (c >> 6), 0x80 | (c & 0x3f)); }
                else { arr.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 0x3f), 0x80 | (c & 0x3f)); }
            }
            return new Uint8Array(arr);
        };
    };
}
window.TextEncoder = TextEncoder;

if (typeof TextDecoder === 'undefined') {
    var TextDecoder = function TextDecoder() {
        this.encoding = 'utf-8';
        this.decode = function(buf) {
            if (!buf || !buf.length) return '';
            var s = '';
            for (var i = 0; i < buf.length; i++) s += String.fromCharCode(buf[i]);
            return s;
        };
    };
}
window.TextDecoder = TextDecoder;
window.parseInt = parseInt;
window.parseFloat = parseFloat;
window.isNaN = isNaN;
window.isFinite = isFinite;
window.encodeURIComponent = encodeURIComponent;
window.decodeURIComponent = decodeURIComponent;
window.encodeURI = encodeURI;
window.decodeURI = decodeURI;

// ── APIs required by Mermaid 11+ ─────────────────────────────────────────────

// Intl — GraalJS does not provide the Intl API by default.
// Mermaid 11+ uses Intl.Segmenter in SplitTextToChars() for grapheme cluster splitting.
// This polyfill provides a minimal Intl object with Segmenter that splits by
// single code points (sufficient for Western text; doesn't handle combined emoji/ZWJ).
if (typeof Intl === 'undefined') {
    var Intl = {};
}

if (!Intl.Segmenter) {
    Intl.Segmenter = function IntlSegmenter(locale, options) {
        this._granularity = (options && options.granularity) || 'grapheme';
    };
    Intl.Segmenter.prototype.segment = function(str) {
        if (str === undefined || str === null) str = '';
        str = String(str);
        var segments = [];
        var i = 0;
        while (i < str.length) {
            var code = str.charCodeAt(i);
            var seg;
            // Handle surrogate pairs (emoji, CJK extension, etc.)
            if (code >= 0xD800 && code <= 0xDBFF && i + 1 < str.length) {
                var lo = str.charCodeAt(i + 1);
                if (lo >= 0xDC00 && lo <= 0xDFFF) {
                    seg = str.substring(i, i + 2);
                    i += 2;
                } else {
                    seg = str[i];
                    i++;
                }
            } else {
                seg = str[i];
                i++;
            }
            segments.push({ segment: seg, index: i - seg.length, input: str });
        }
        // Make the result iterable (for-of / spread) via Symbol.iterator
        var result = {
            _segments: segments,
            containing: function(idx) {
                for (var j = 0; j < segments.length; j++) {
                    if (segments[j].index === idx) return segments[j];
                }
                return undefined;
            }
        };
        if (typeof Symbol !== 'undefined' && Symbol.iterator) {
            result[Symbol.iterator] = function() {
                var pos = 0;
                return {
                    next: function() {
                        if (pos < segments.length) {
                            return { value: segments[pos++], done: false };
                        }
                        return { value: undefined, done: true };
                    }
                };
            };
        }
        return result;
    };
    Intl.Segmenter.supportedLocalesOf = function() { return []; };
}

// Intl.NumberFormat — minimal stub (used by some Mermaid axis/chart code)
if (!Intl.NumberFormat) {
    Intl.NumberFormat = function(locale, options) {
        this._options = options || {};
    };
    Intl.NumberFormat.prototype.format = function(n) { return String(n); };
    Intl.NumberFormat.supportedLocalesOf = function() { return []; };
}

// Intl.DateTimeFormat — minimal stub
if (!Intl.DateTimeFormat) {
    Intl.DateTimeFormat = function(locale, options) {
        this._options = options || {};
    };
    Intl.DateTimeFormat.prototype.format = function(d) {
        if (d instanceof Date) return d.toISOString();
        return String(d);
    };
    Intl.DateTimeFormat.supportedLocalesOf = function() { return []; };
}

// Intl.Collator — minimal stub (used by sorting in some diagram types)
if (!Intl.Collator) {
    Intl.Collator = function(locale, options) {};
    Intl.Collator.prototype.compare = function(a, b) {
        if (a < b) return -1;
        if (a > b) return 1;
        return 0;
    };
    Intl.Collator.supportedLocalesOf = function() { return []; };
}

window.Intl = Intl;

// Object.hasOwn — ES2022 polyfill (not available in GraalJS / Java 8)
if (!Object.hasOwn) {
    Object.hasOwn = function(obj, prop) {
        return Object.prototype.hasOwnProperty.call(obj, prop);
    };
}

// Array.prototype.at — ES2022 polyfill
if (!Array.prototype.at) {
    Array.prototype.at = function(index) {
        index = Math.trunc(index) || 0;
        if (index < 0) index += this.length;
        if (index < 0 || index >= this.length) return undefined;
        return this[index];
    };
}

// String.prototype.at — ES2022 polyfill
if (!String.prototype.at) {
    String.prototype.at = function(index) {
        index = Math.trunc(index) || 0;
        if (index < 0) index += this.length;
        if (index < 0 || index >= this.length) return undefined;
        return this[index];
    };
}

// String.prototype.replaceAll — ES2021 polyfill
if (!String.prototype.replaceAll) {
    String.prototype.replaceAll = function(search, replacement) {
        if (search instanceof RegExp) {
            if (!search.global) throw new TypeError('String.prototype.replaceAll called with a non-global RegExp');
            return this.replace(search, replacement);
        }
        return this.split(search).join(replacement);
    };
}

// structuredClone — deep-copy that handles circular references
// (Mermaid block diagrams pass objects with DOM element back-refs which are cyclic)
if (typeof structuredClone === 'undefined') {
    var structuredClone = function(obj) {
        if (obj === undefined || obj === null) return obj;
        if (typeof obj !== 'object') return obj;

        // Try fast JSON round-trip first
        try {
            return JSON.parse(JSON.stringify(obj));
        } catch (e) {
            // Circular reference — do a manual deep clone
            var seen = [];
            function deepClone(val) {
                if (val === undefined || val === null) return val;
                if (typeof val !== 'object') return val;
                if (val instanceof Date) return new Date(val.getTime());
                if (val instanceof RegExp) return new RegExp(val);
                // Skip DOM-like elements (have tagName or nodeType)
                if (val.nodeType !== undefined || val.tagName !== undefined) return null;
                // Circular reference check
                for (var s = 0; s < seen.length; s++) {
                    if (seen[s] === val) return null;
                }
                seen.push(val);
                if (Array.isArray(val)) {
                    var arr = [];
                    for (var i = 0; i < val.length; i++) {
                        arr.push(deepClone(val[i]));
                    }
                    return arr;
                }
                var copy = {};
                var keys = Object.keys(val);
                for (var k = 0; k < keys.length; k++) {
                    try { copy[keys[k]] = deepClone(val[keys[k]]); }
                    catch (ce) { /* skip uncloneable props */ }
                }
                return copy;
            }
            return deepClone(obj);
        }
    };
    window.structuredClone = structuredClone;
}

// queueMicrotask — execute immediately in our synchronous environment
if (typeof queueMicrotask === 'undefined') {
    var queueMicrotask = function(fn) { fn(); };
    window.queueMicrotask = queueMicrotask;
}

// crypto.randomUUID / crypto.getRandomValues
if (typeof crypto === 'undefined') {
    var crypto = {
        getRandomValues: function(arr) {
            for (var i = 0; i < arr.length; i++) {
                arr[i] = Math.floor(Math.random() * 256);
            }
            return arr;
        },
        randomUUID: function() {
            // v4 UUID
            var bytes = new Uint8Array(16);
            crypto.getRandomValues(bytes);
            bytes[6] = (bytes[6] & 0x0f) | 0x40;
            bytes[8] = (bytes[8] & 0x3f) | 0x80;
            var hex = [];
            for (var i = 0; i < 16; i++) {
                var h = bytes[i].toString(16);
                hex.push(h.length < 2 ? '0' + h : h);
            }
            return hex[0]+hex[1]+hex[2]+hex[3]+'-'+hex[4]+hex[5]+'-'+hex[6]+hex[7]+'-'+hex[8]+hex[9]+'-'+hex[10]+hex[11]+hex[12]+hex[13]+hex[14]+hex[15];
        }
    };
    window.crypto = crypto;
}

// AbortController / AbortSignal stubs
function AbortController() {
    this.signal = { aborted: false, addEventListener: function() {}, removeEventListener: function() {} };
}
AbortController.prototype.abort = function() { this.signal.aborted = true; };
window.AbortController = AbortController;

// WeakRef stub (used by some Mermaid internals)
if (typeof WeakRef === 'undefined') {
    var WeakRef = function(target) { this._target = target; };
    WeakRef.prototype.deref = function() { return this._target; };
    window.WeakRef = WeakRef;
}

// FinalizationRegistry stub
if (typeof FinalizationRegistry === 'undefined') {
    var FinalizationRegistry = function(callback) {};
    FinalizationRegistry.prototype.register = function() {};
    FinalizationRegistry.prototype.unregister = function() {};
    window.FinalizationRegistry = FinalizationRegistry;
}

// document.createNodeIterator (used by DOMPurify in Mermaid 11)
if (!document.createNodeIterator) {
    document.createNodeIterator = function(root, whatToShow, filter) {
        var nodes = [];
        var idx = -1;
        function collect(node) {
            if (!node) return;
            var dominated = false;
            if (whatToShow) {
                if ((whatToShow & 1) && node.nodeType === 1) dominated = true;
                if ((whatToShow & 4) && node.nodeType === 3) dominated = true;
                if ((whatToShow & 128) && node.nodeType === 8) dominated = true;
                if ((whatToShow & 64) && node.nodeType === 7) dominated = true;
                if ((whatToShow & 8) && node.nodeType === 4) dominated = true;
            } else {
                dominated = true;
            }
            if (dominated) nodes.push(node);
            var children = node.childNodes || [];
            for (var i = 0; i < children.length; i++) {
                collect(children[i]);
            }
        }
        collect(root);
        return {
            nextNode: function() {
                idx++;
                return idx < nodes.length ? nodes[idx] : null;
            },
            previousNode: function() {
                idx--;
                return idx >= 0 ? nodes[idx] : null;
            }
        };
    };
}

// document.implementation (used by DOMPurify)
if (!document.implementation) {
    document.implementation = {
        createHTMLDocument: function(title) {
            var doc = {
                nodeType: 9,
                documentElement: createDomElement('html'),
                body: createDomElement('body'),
                head: createDomElement('head'),
                createElement: function(name) { var e = createDomElement(name); e.ownerDocument = doc; return e; },
                createElementNS: function(ns, name) { var e = createDomElement(name, ns); e.ownerDocument = doc; return e; },
                createTextNode: function(text) { return { nodeType: 3, nodeName: '#text', textContent: text, ownerDocument: doc }; },
                createComment: function(data) { return { nodeType: 8, nodeName: '#comment', textContent: data, ownerDocument: doc }; },
                createDocumentFragment: function() { var f = createDomElement('#fragment'); f.nodeType = 11; f.nodeName = '#document-fragment'; f.ownerDocument = doc; return f; },
                createNodeIterator: document.createNodeIterator,
                querySelector: function(sel) { return _querySelector(doc.documentElement, sel); },
                querySelectorAll: function(sel) { return _querySelectorAll(doc.documentElement, sel); },
                getElementById: function(id) { return _querySelector(doc.documentElement, '#' + id); },
                getElementsByTagName: function(name) { return _querySelectorAll(doc.documentElement, name); },
                importNode: function(node, deep) { return node.cloneNode ? node.cloneNode(deep) : node; }
            };
            doc.documentElement.appendChild(doc.head);
            doc.documentElement.appendChild(doc.body);
            doc.documentElement.ownerDocument = doc;
            doc.body.ownerDocument = doc;
            doc.head.ownerDocument = doc;
            if (title) { var t = doc.createElement('title'); t.textContent = title; doc.head.appendChild(t); }
            return doc;
        }
    };
}

// document.importNode
if (!document.importNode) {
    document.importNode = function(node, deep) {
        return node.cloneNode ? node.cloneNode(deep) : node;
    };
}

// Signal that the shim loaded successfully
'browser-shim.js loaded';

