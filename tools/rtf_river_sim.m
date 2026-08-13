clear; close

%% Rivers from the game
% ==== River 1 ====
% Start: (3030.0, -7561.0), End: (-568.0, 279.0),  Length: 8626.192
% Warp settings:
%     Seed: 1919241261
%     Scale: 172.0
%     Frequency: 9.983494E-4
%     Lower alpha boundary: 0.1
%     Upper alpha boundary: 0.85
% Carver settings:
%     Fade: 0.5186856
%     Bed Width: 0.5 to 17.0
%     Banks Width: 1.25 to 40.0
%     Valley Width: 239.81697122493227 to 239.81697122493227
%     Valley S-Curve coeffs: L=4.0, U=5.0
networks(1).river = makeRiver(3030.0, -7561.0, -568.0, 279.0);
networks(1).warp = makeWarp(1919241261, 172.0, 9.983494e-4, 0.1, 0.85);
networks(1).fade = 0.5186856;
networks(1).bedWidth = 17.0;
networks(1).banksWidth = 40.0;
networks(1).valleyWidth = 239.81697122493227;
networks(1).valleyCurveL = 4.0;
networks(1).valleyCurveU = 5.0;
% ==== River 2 ====
% Start: (4734.8306, -6402.36), End: (1551.7415, -4339.8916),  Length: 3792.866
% Warp settings:
%     Seed: 1424460979
%     Scale: 111.799995
%     Frequency: 6.4892706E-4
%     Lower alpha boundary: 0.15
%     Upper alpha boundary: 0.75
% Carver settings:
%     Fade: 0.61275774
%     Bed Width: 0.5 to 11.0
%     Banks Width: 1.25 to 27.0
%     Valley Width: 266.5442476166012 to 266.5442476166012
%     Valley S-Curve coeffs: L=3.0, U=0.25
riv.river = makeRiver(4734.8306, -6402.36, 1551.7415, -4339.8916);
riv.warp = makeWarp(1424460979, 111.799995, 6.4892706E-4, 0.15, 0.75);
riv.fade = 0.61275774;
riv.bedWidth = 11.0;
riv.banksWidth = 27.0;
riv.valleyWidth = 266.5442476166012;
riv.valleyCurveL = 3.0;
riv.valleyCurveU = 0.25;
riv.children = [];

networks(1).children = [riv];

%% ============== CARVER HEATMAP ============
HEAT_STEP = 4;                          % heatmap grid step (blocks)
MARGIN_B  = 1600;                       % raster margin around all rivers (fork tips)
USE_VALLEY = false;                      % true: wide valley mask; false: bed/banks (river proper)

[xr, zr, rootZmin, rootZmax] = networkRaster(networks, MARGIN_B, HEAT_STEP);
NF = numel(networks);
mask = ones(numel(zr), numel(xr));
for iz = 1:numel(zr)
    z = zr(iz);
    m1 = ones(size(xr));
    for i = 1:NF
        if z < rootZmin(i)-200 || z > rootZmax(i)+200, continue; end
        if USE_VALLEY
            m1 = min(m1, carveAlpha(networks(i), xr, z));
        else
            m1 = min(m1, carveBed(networks(i), xr, z));
        end
    end
    mask(iz, :) = m1;
end
fprintf('done\n');

figure('Name', sprintf('carver riverMask - REAL simplex (valley=%d)', USE_VALLEY), 'Color','w');
imagesc(xr, zr, mask);
set(gca,'YDir','reverse'); axis equal tight; grid on;
colormap(turbo); caxis([0 1]); colorbar;
hold on;

%% ============== FLOW-DIRECTION QUIVER OVERLAY ============
% Probe every block of the heatmap raster with a caller-supplied flow function
% and overlay a quiver map on the CURRENT axes (the heatmap figure above).
%   probeFlow(networks, step, flowFunc, color)
%     networks : struct array of river networks (same as the heatmap)
%     step     : probe spacing in blocks (must divide the heatmap grid sensibly)
%     flowFunc : function handle  [dx, dz] = flowFunc(networks, x, z)
%                -> returns the direction vector at (x, z); NaN/NaN skips the block
%     color    : optional quiver color (default 'k')
% EXAMPLES:
probeFlow(networks, 16, @flowGradientNormal, 'g');       % gradient-normal (raw angle, no end-blend/bank)
probeFlow(networks, 16, @flowSideGradient, 'b');          % side-based sign
probeFlow(networks, 16, @flowSignedDistGradient, 'm');    % gradient of SIGNED distance, rotate 90

%% ============== QUICK FLIP-CAUSE CHECK (temporary) ============
% At the two sharp-wiggle regions, is the raw gradient-perpendicular rotated
% more than 90 deg from the axis direction (which is what forces the flip)?
CHECK_REGIONS = [8, -1110; 1720, -4745];                 % [x, z] region centers
for ri = 1:size(CHECK_REGIONS, 1)
    cx = CHECK_REGIONS(ri, 1); cz = CHECK_REGIONS(ri, 2);
    half = 150; step = 10;
    xs = cx - half : step : cx + half;
    zs = cz - half : step : cz + half;
    dots = []; cosAngs = [];
    for iz = 1:numel(zs)
        for ix = 1:numel(xs)
            x = xs(ix); z = zs(iz);
            [n, d2, tw] = nearestNetwork(networks, x, z);
            if isempty(n) || d2 > scaledSize2(tw, n, n.banksWidth^2, 1.25^2), continue; end
            rv = n.river; ux = rv.ndx; uz = rv.ndz;
            h = 1.0;
            gx = (shiftedDistSqFull(networks, n, x+h, z) - shiftedDistSqFull(networks, n, x-h, z)) / (2.0*h);
            gz = (shiftedDistSqFull(networks, n, x, z+h) - shiftedDistSqFull(networks, n, x, z-h)) / (2.0*h);
            mag = sqrt(gx*gx + gz*gz);
            if mag <= 1.0e-5, continue; end
            fx = -gz/mag; fz = gx/mag;                   % RAW flow BEFORE the flip
            dots(end+1) = fx*ux + fz*uz;                 % <0 => flip happened
            cosAngs(end+1) = dots(end);
        end
    end
    nFlip = sum(dots < 0); nTot = numel(dots);
    fprintf('region [%d %d]: %d/%d points flipped (%.1f%%), mean|angle|=%.1f deg, max|angle|=%.1f deg\n', ...
        cx, cz, nFlip, nTot, 100*nFlip/max(nTot,1), mean(acosd(min(1,abs(cosAngs)))), max(acosd(min(1,abs(cosAngs)))));
end

%% River building functions
function rv = makeRiver(x1, z1, x2, z2)
    rv.x1 = x1; rv.z1 = z1; rv.x2 = x2; rv.z2 = z2;
    rv.dx = x2 - x1;      rv.dz = z2 - z1;
    rv.length  = sqrt(rv.dx^2 + rv.dz^2);
    rv.length2 = rv.length^2;
    rv.ndx = rv.dx/rv.length;    rv.ndz = rv.dz/rv.length;
    rv.normX = rv.ndz;           rv.normZ = -rv.ndx;
    rv.minX = min(x1, x2);  rv.maxX = max(x1, x2);
    rv.minZ = min(z1, z2);  rv.maxZ = max(z1, z2);
end

function wp = makeWarp(seed, scale, frequency, lower, upper)
    wp.seed = int32(seed);
    wp.scale = scale;
    wp.frequency = frequency;
    wp.lower = lower;
    wp.upper = upper;
    wp.lowerRange = 1.0/lower;
    wp.upperRange = 1.0/(1.0 - upper);
end

%% Distance calculation functions from the game
function t = distanceOnLine(x, z, rv)
    t = ((x - rv.x1).*rv.dx + (z - rv.z1).*rv.dz) ./ rv.length2;
end

function d2 = getDistSq(x, z, rv, t)
    if isscalar(z), z = z .* ones(size(x)); end
    d2 = zeros(size(x));
    m0 = t <= 0;  d2(m0) = (x(m0)-rv.x1).^2 + (z(m0)-rv.z1).^2;
    m1 = t >= 1;  d2(m1) = (x(m1)-rv.x2).^2 + (z(m1)-rv.z2).^2;
    mm = ~(m0 | m1);
    px = rv.x1 + t(mm).*rv.dx;   pz = rv.z1 + t(mm).*rv.dz;
    d2(mm) = (x(mm)-px).^2 + (z(mm)-pz).^2;
end

%% Carver math from the game
function [dx, dz] = shiftAt(rv, wp, x, z)
    % RiverWarp.getOffset - single-precision simplex noise inside!
    dx = zeros(size(x)); dz = zeros(size(x));
    t = distanceOnLine(x, z, rv);
    ok = t >= 0 & t <= 1;                     % warp.test
    if ~any(ok), return; end
    tq = t(ok);
    alpha1 = warpAlpha(tq, wp);
    dist = alpha1 .* wp.scale;
    px = single(x(ok)) .* single(wp.frequency);
    pz = single(z(ok)) .* single(wp.frequency);
    noise = double(simplex2_sample(px, pz, int32(wp.seed)));
    dxx = rv.normX .* noise .* dist;
    dzz = rv.normZ .* noise .* dist;
    a2 = min(max(tq, 0), 0.075) ./ 0.075;
    fac = rv.length .* 4e-4;
    wF = 8 .* fac;
    wD = min(max(a2 .* 25 .* fac, 2), 45);
    rad = noise + tq .* 6.2831855 .* wF;
    dxx = dxx + rv.normX .* cos(rad) .* wD;
    dzz = dzz + rv.normZ .* sin(rad) .* wD;
    dx(ok) = dxx;  dz(ok) = dzz;
end

function a = warpAlpha(t, wp)
    % RiverWarp.getWarpAlpha
    a = zeros(size(t));
    ok = t >= 0 & t <= 1;
    tq = t(ok);
    out = zeros(size(tq));
    lo = tq < wp.lower; out(lo) = tq(lo) .* wp.lowerRange;
    hi = tq > wp.upper; out(hi) = (1 - tq(hi)) .* wp.upperRange;
    out(~lo & ~hi) = 1;
    a(ok) = out;
end

function v = carveAlpha(n, x, z)
    % RiverCarver.carve valley alpha at the ENTERING point (folded by all ancestors),
    % then recurse into children with the OWN-folded point (Network.carve fold chain).
    rv = n.river; wp = n.warp;
    v = ones(size(x));
    if isscalar(z), z = z .* ones(size(x)); end
    xw = x; zw = z;
    t = distanceOnLine(x, z, rv);
    keep = t >= -0.02 & t <= 1.02;
    if any(keep)
        dRaw = getDistSq(x, z, rv, t);            % pd2: entering point (ancestor-folded)
        dW = dRaw;
        st = t >= 0 & t <= 1;                     % warp.test
        if any(st)
            zs = z .* ones(size(x));
            [dx, dz] = shiftAt(rv, wp, x(st), zs(st));
            xw(st) = x(st) + dx;  zw(st) = zs(st) + dz;
            tw = distanceOnLine(xw(st), zw(st), rv);
            dW(st) = getDistSq(xw(st), zw(st), rv, tw);  % d2: own-folded point
        end
        dmin = min(dRaw, dW);
        va = 1 - dmin ./ n.valleyWidth^2;
        va(dmin >= n.valleyWidth^2) = 0;
        va(~keep) = 0;
        ok = va > 0;
        if any(ok)
            vq = va(ok);
            cr = vq .^ (n.valleyCurveL + n.valleyCurveU .* vq);
            v(ok) = 1 - cr;
        end
    end
    for c = 1:numel(n.children)
        if ~isempty(n.children(c).river)
            v = min(v, carveAlpha(n.children(c), xw, zw));
        end
    end
end

function v = carveBed(n, x, z)
    % Banks/bed carve alpha ("river proper"): d2 of the SHIFTED point vs the scaled
    % banksWidth (0 = bed center, 1 = outside the bank edge), then recurse like carveAlpha.
    rv = n.river; wp = n.warp;
    v = ones(size(x));
    if isscalar(z), z = z .* ones(size(x)); end
    xw = x; zw = z;
    t = distanceOnLine(x, z, rv);
    st = t >= 0 & t <= 1;
    if any(st)
        [dx, dz] = shiftAt(rv, wp, x(st), z(st));
        xw(st) = x(st) + dx;  zw(st) = z(st) + dz;
        tw = distanceOnLine(xw(st), zw(st), rv);
        d2 = getDistSq(xw(st), zw(st), rv, tw);
        bs2 = scaledSize2(tw, n, n.banksWidth^2, 1.25^2);   % banksWidth Range(1.5625, bankWidth^2)
        bedv = d2 ./ bs2;                                   % 0 = deep center, 1 = bank edge
        bedv(bedv > 1) = 1;
        v(st) = bedv;
    end
    for c = 1:numel(n.children)
        if ~isempty(n.children(c).river)
            v = min(v, carveBed(n.children(c), xw, zw));
        end
    end
end

function s2 = scaledSize2(t, n, max2, min2)
    % RiverCarver.getScaledSize: size grows from min2 to max2 over the first `fade` of the river
    s2 = max2 .* ones(size(t));
    s2(t < 0) = min2;
    grow = t >= 0 & t < n.fade;
    if any(grow)
        s2(grow) = min2 + (max2 - min2) .* (t(grow) .* (1.0 / n.fade));
    end
end

function [xmn, xmx, zmn, zmx] = treeBounds(n)
    % AABB over this network and its whole subtree (fork tips included)
    rv = n.river;
    xmn = min(rv.x1, rv.x2); xmx = max(rv.x1, rv.x2);
    zmn = min(rv.z1, rv.z2); zmx = max(rv.z1, rv.z2);
    for c = 1:numel(n.children)
        if isempty(n.children(c).river), continue; end
        [cxmn, cxmx, czmn, czmx] = treeBounds(n.children(c));
        xmn = min(xmn, cxmn); xmx = max(xmx, cxmx);
        zmn = min(zmn, czmn); zmx = max(zmx, czmx);
    end
end

function [xr, zr, rootZmin, rootZmax] = networkRaster(networks, margin, step)
    % The raster the heatmap uses: subtree AABB of every root network + margin.
    NF = numel(networks);
    xmn = inf; xmx = -inf; zmn = inf; zmx = -inf;
    rootZmin = zeros(NF, 1); rootZmax = zeros(NF, 1);
    for i = 1:NF
        [bxmn, bxmx, bzmn, bzmx] = treeBounds(networks(i));
        xmn = min(xmn, bxmn); xmx = max(xmx, bxmx);
        zmn = min(zmn, bzmn); zmx = max(zmx, bzmx);
        rootZmin(i) = bzmn; rootZmax(i) = bzmx;
    end
    xr = floor(xmn)-margin : step : ceil(xmx)+margin;
    zr = floor(zmn)-margin : step : ceil(zmx)+margin;
end

function probeFlow(networks, step, flowFunc, color, arrowLen)
    % Overlay a quiver map of a caller-supplied flow function on the CURRENT axes.
    %   probeFlow(networks, step, flowFunc, color, arrowLen)
    %     networks : struct array of river networks (same layout as the heatmap)
    %     step     : probe spacing in blocks (grid step)
    %     flowFunc : function handle  [dx, dz] = flowFunc(networks, x, z)
    %                -> direction vector at block (x, z); NaN/NaN skips the block
    %     color    : optional quiver color (default 'k')
    %     arrowLen : optional arrow length in blocks (default 20); every arrow is
    %                normalized to this length so all methods compare at equal scale
    % Uses the SAME raster as the heatmap (networkRaster with the heatmap margin)
    % so probes line up with the mask. All arrows are gathered into ONE quiver call.
    if nargin < 4, color = 'k'; end
    if nargin < 5, arrowLen = 20; end
    [xr, zr] = networkRaster(networks, 1600, step);
    ax = gca; hold(ax, 'on');
    X = []; Y = []; U = []; V = [];
    for iz = 1:numel(zr)
        z = zr(iz);
        for ix = 1:numel(xr)
            [dx, dz] = flowFunc(networks, xr(ix), z);
            if isnan(dx) || isnan(dz), continue; end
            L = hypot(dx, dz);
            if L <= 0, continue; end
            X(end+1) = xr(ix); Y(end+1) = z;
            U(end+1) = dx / L * arrowLen; V(end+1) = dz / L * arrowLen;
        end
    end
    if ~isempty(U)
        quiver(ax, X - U/2, Y - V/2, U, V, 0, 'Color', color, 'LineWidth', 0.7, 'MaxHeadSize', 0.4);
    end
end

function [dx, dz] = flowGradientNormal(networks, x, z)
    % EXAMPLE flow function #3 (port of the old connector's dirVectorGradient, RAW):
    % flow = unit vector perpendicular to the gradient of the shifted-distance field
    % of the nearest river (full ancestor fold chain). Central differences h=1, same
    % as the old connector. NO end-blend, NO bank correction, NO edge-flip: pure
    % gradient normal, aligned to the river axis by a dot<0 flip.
    [n, d2, tw] = nearestNetwork(networks, x, z);
    if isempty(n) || d2 > scaledSize2(tw, n, n.banksWidth^2, 1.25^2)
        dx = NaN; dz = NaN; return;
    end
    rv = n.river;
    ux = rv.ndx; uz = rv.ndz;
    h = 1.0;
    gx = (shiftedDistSqFull(networks, n, x + h, z) - shiftedDistSqFull(networks, n, x - h, z)) / (2.0 * h);
    gz = (shiftedDistSqFull(networks, n, x, z + h) - shiftedDistSqFull(networks, n, x, z - h)) / (2.0 * h);
    mag = sqrt(gx * gx + gz * gz);
    fx = -gz; fz = gx;                       % perpendicular to the gradient
    if mag > 1.0e-5
        fx = fx / mag; fz = fz / mag;
    else
        fx = ux; fz = uz;                    % dead zone: fall back to the axis direction
    end
    if fx * ux + fz * uz < 0.0               % align with the axis direction (raw flip)
        fx = -fx; fz = -fz;
    end
    dx = fx; dz = fz;
end

function d2 = shiftedDistSqFull(networks, best, x, z)
    % shiftedDistSqFull(best, x, z) - the shifted squared distance of the FIXED
    % network `best` at an arbitrary point (x,z): fold (x,z) through the ancestor
    % chain (root -> ... -> parent, the fold stack), then apply `best`'s own warp,
    % then getDistSq. Mirrors RtfFlow.shiftedDistSqFull (global Domain warp is not
    % simulated - the sim folds per-node warps only, like nearestInTree does).
    d2 = inf;
    for i = 1:numel(networks)
        d2 = treeShiftedDist(networks(i), best, x, z);
        if ~isinf(d2), return; end
    end
end

function d2 = treeShiftedDist(n, best, x, z)
    % Walk the tree to `best`: fold (x,z) by each ancestor's own warp (the fold
    % stack), and once `best` is reached apply ITS warp too, then getDistSq.
    if isequal(n, best)
        rv = n.river; wp = n.warp;
        t = distanceOnLine(x, z, rv);
        xw = x; zw = z;
        if t >= 0 && t <= 1
            [dx, dz] = shiftAt(rv, wp, x, z);
            xw = x + dx; zw = z + dz;
            t = distanceOnLine(xw, zw, rv);
        end
        d2 = getDistSq(xw, zw, rv, t);
        return;
    end
    t = distanceOnLine(x, z, n.river);
    xw = x; zw = z;
    if t >= 0 && t <= 1
        [dx, dz] = shiftAt(n.river, n.warp, x, z);
        xw = x + dx; zw = z + dz;
    end
    d2 = inf;
    for c = 1:numel(n.children)
        if isempty(n.children(c).river), continue; end
        d2 = treeShiftedDist(n.children(c), best, xw, zw);
        if ~isinf(d2), return; end
    end
end

function [dx, dz] = flowSideGradient(networks, x, z)
    % Gradient of the SHIFTED squared distance (same field as flowGradientNormal),
    % but the perpendicular's SIGN is chosen by the SIGNED lateral distance of the
    % warped point onto the river normal — which side of the straight axis it is
    % on — instead of the axis dot<0 flip. PREDICTION: equivalent to the axis flip
    % away from folds, but BOTH snap at the two wiggle regions (the snap is the
    % field's medial axis, structural — sign(w) does not flip there), so this and
    % the gradient method should overlap almost everywhere; the test confirms it.
    [n, d2, tw] = nearestNetwork(networks, x, z);
    if isempty(n) || d2 > scaledSize2(tw, n, n.banksWidth^2, 1.25^2)
        dx = NaN; dz = NaN; return;
    end
    rv = n.river;
    h = 1.0;
    gx = (shiftedDistSqFull(networks, n, x+h, z) - shiftedDistSqFull(networks, n, x-h, z)) / (2.0*h);
    gz = (shiftedDistSqFull(networks, n, x, z+h) - shiftedDistSqFull(networks, n, x, z-h)) / (2.0*h);
    mag = sqrt(gx*gx + gz*gz);
    if mag <= 1.0e-5
        dx = rv.ndx; dz = rv.ndz; return;             % dead zone -> axis dir
    end
    % Warped (shifted) point and its signed lateral distance on the river normal.
    [xw, zw, tw] = shiftedPointFull(networks, n, x, z);
    w = (xw - (rv.x1 + tw*rv.dx))*rv.normX + (zw - (rv.z1 + tw*rv.dz))*rv.normZ;
    s = sign(w);  if s == 0, s = 1; end               % on-axis: pick one
    dx =  s * (-gz/mag);                               % = -sign(w) * f_raw
    dz =  s * (gx/mag);
end

function [xw, zw, tw] = shiftedPointFull(networks, best, x, z)
    % Fold (x,z) through the ancestor chain of `best` (the fold STACK), then apply
    % best's own warp — returning the shifted point and its section t. Mirrors
    % treeShiftedDist exactly but returns the point instead of getDistSq.
    for i = 1:numel(networks)
        [found, xw, zw, tw] = foldToPoint(networks(i), best, x, z);
        if found, return; end
    end
    rv = best.river;                                   % fallback (shouldn't occur)
    tw = distanceOnLine(x, z, rv); xw = x; zw = z;
end

function [found, xw, zw, tw] = foldToPoint(n, best, x, z)
    if isequal(n, best)
        rv = n.river; wp = n.warp;
        t = distanceOnLine(x, z, rv);
        xw = x; zw = z;
        if t >= 0 && t <= 1
            [dx, dz] = shiftAt(rv, wp, x, z);
            xw = x + dx; zw = z + dz;
            t = distanceOnLine(xw, zw, rv);
        end
        tw = t; found = true; return;
    end
    t = distanceOnLine(x, z, n.river);
    xw = x; zw = z;
    if t >= 0 && t <= 1
        [dx, dz] = shiftAt(n.river, n.warp, x, z);
        xw = x + dx; zw = z + dz;
    end
    found = false;
    for c = 1:numel(n.children)
        if isempty(n.children(c).river), continue; end
        [found, xw, zw, tw] = foldToPoint(n.children(c), best, xw, zw);
        if found, return; end
    end
end

function [dx, dz] = flowSignedDistGradient(networks, x, z)
    % Gradient of the SIGNED lateral distance (not squared), rotated 90 deg. The
    % signed-distance field w(x,z) is single-valued and its gradient points toward
    % increasing w (the +n^ side) on BOTH banks — unlike d2=w^2 whose gradient
    % flips with sign(w). So perp(∇w) should be a consistent direction everywhere
    % (no per-point sign selection). Test: is it continuous at the folds, and does
    % the rotation give downstream (else one global flip)? 4 signed-distance probes
    % through the fold chain (reuses shiftedPointFull), then rotate 90.
    [n, d2, tw] = nearestNetwork(networks, x, z);
    if isempty(n) || d2 > scaledSize2(tw, n, n.banksWidth^2, 1.25^2)
        dx = NaN; dz = NaN; return;
    end
    rv = n.river;
    h = 1.0;
    wR = signedLatDist(networks, n, x + h, z);
    wL = signedLatDist(networks, n, x - h, z);
    wU = signedLatDist(networks, n, x, z + h);
    wD = signedLatDist(networks, n, x, z - h);
    gx = (wR - wL) / (2.0*h);
    gz = (wU - wD) / (2.0*h);
    mag = sqrt(gx*gx + gz*gz);
    if mag <= 1.0e-5
        dx = rv.ndx; dz = rv.ndz; return;             % dead zone -> axis dir
    end
    dx = -gz / mag;                                   % perp(∇w); sign TBD (flip if upstream)
    dz =  gx / mag;
end

function w = signedLatDist(networks, best, x, z)
    % Signed lateral distance of the warped point onto the river normal: fold (x,z)
    % through best's ancestor chain + own warp, then project the offset onto n^.
    [xw, zw, tw] = shiftedPointFull(networks, best, x, z);
    rv = best.river;
    w = (xw - (rv.x1 + tw*rv.dx))*rv.normX + (zw - (rv.z1 + tw*rv.dz))*rv.normZ;
end

function [n, d2, tw] = nearestNetwork(networks, x, z)
    % The nearest river (walking children recursively) by SHIFTED distance
    % |S(Q) - axis|^2 - the SAME metric the depth calculation uses (carveBed).
    % Returns the network, its shifted d2, and the shifted section t.
    n = []; d2 = inf; tw = NaN;
    for i = 1:numel(networks)
        [cn, cd2, ctw] = nearestInTree(networks(i), x, z);
        if cd2 < d2
            d2 = cd2; n = cn; tw = ctw;
        end
    end
end

function [n, d2, tw] = nearestInTree(n, x, z)
    % Shifted distance of (x,z) to this river: fold by the node's own warp,
    % then getDistSq to the axis. Recurses children with the OWN-FOLDED point,
    % mirroring the Network.carve fold chain exactly like carveBed does.
    rv = n.river; wp = n.warp;
    t = distanceOnLine(x, z, rv);
    xw = x; zw = z; tw = t;
    if t >= 0 && t <= 1
        [dx, dz] = shiftAt(rv, wp, x, z);
        xw = x + dx; zw = z + dz;
        tw = distanceOnLine(xw, zw, rv);
    end
    d2 = getDistSq(xw, zw, rv, tw);
    bestN = n;
    for c = 1:numel(n.children)
        if isempty(n.children(c).river), continue; end
        [cn, cd2, ctw] = nearestInTree(n.children(c), xw, zw);
        if cd2 < d2
            d2 = cd2; bestN = cn; tw = ctw;
        end
    end
    n = bestN;
end

%% ---- exact copies of rtf_sim2.m noise local functions ------------------
function n = simplex2_sample(x, y, seed)
    n = singleSimplex(x, y, seed, single(99.83685));
end
function n = singleSimplex(x, y, seed, scaler)
    if ~isa(x, 'single'), x = single(x); end
    if ~isa(y, 'single'), y = single(y); end
    F2 = single(0.36602542); G2 = single(0.21132487); H2 = single(0.42264974);
    t  = (x + y) .* F2;
    i  = noiseUtil_floor(x + t);
    j  = noiseUtil_floor(y + t);
    t  = (single(i) + single(j)) .* G2;
    X0 = single(i) - t;
    Y0 = single(j) - t;
    x2 = x - X0;
    y2 = y - Y0;
    i2 = int32(x2 > y2);
    j2 = int32(x2 <= y2);
    x3 = x2 - single(i2) + G2;
    y3 = y2 - single(j2) + G2;
    x4 = x2 - single(1) + H2;
    y4 = y2 - single(1) + H2;
    t0 = single(0.5) - x2.*x2 - y2.*y2;    k0 = t0 > 0;
    t0 = t0 .* t0;
    n0 = zeros(size(x), 'single');
    n0(k0) = t0(k0) .* t0(k0) .* grad24(seed, i(k0), j(k0), x2(k0), y2(k0));
    t2 = single(0.5) - x3.*x3 - y3.*y3;    k2 = t2 > 0;
    t2 = t2 .* t2;
    n2 = zeros(size(x), 'single');
    n2(k2) = t2(k2) .* t2(k2) .* grad24(seed, i(k2)+i2(k2), j(k2)+j2(k2), x3(k2), y3(k2));
    t3 = single(0.5) - x4.*x4 - y4.*y4;    k3 = t3 > 0;
    t3 = t3 .* t3;
    n3 = zeros(size(x), 'single');
    n3(k3) = t3(k3) .* t3(k3) .* grad24(seed, i(k3)+int32(1), j(k3)+int32(1), x4(k3), y4(k3));
    n = scaler .* (n0 + n2 + n3);
end
function g = grad24(seed, i, j, xd, yd)
    h = hash2D(seed, i, j);
    lo = mod(h, 4194304);
    f  = single(lo) .* single(1.3333334);
    idx = floor(double(f));
    sel = mod(idx, 32);
    G = gradTable();
    if isrow(sel)
        g = single(xd) .* reshape(G(sel+1,1), size(xd)) + single(yd) .* reshape(G(sel+1,2), size(yd));
    else
        g = single(xd) .* G(sel+1,1) + single(yd) .* G(sel+1,2);
    end
end
function h = hash2D(seed, x, y)
    x = double(x); y = double(y);
    s = mod(1619 .* x, 4294967296);
    t = mod(31337 .* y, 4294967296);
    h = xorw32(xorw32(seedword(seed), s), t);
    h = mul32(mul32(h, h), h);
    h = mul32(h, 60493);
    neg = h >= 2147483648;
    sh = floor((h - neg .* 4294967296) / 8192);
    sh = sh + 4294967296 .* (sh < 0);
    h = xorw32(h, sh);
end
function r = xorw32(a, b)
    r = double(bitxor(uint64(a), uint64(b)));
end
function u = seedword(sd)
    sd = double(sd);
    u = sd + 4294967296 .* (sd < 0);
end
function r = mul32(a, b)
    a0 = mod(a, 65536); a1 = (a - a0) / 65536;
    b0 = mod(b, 65536); b1 = (b - b0) / 65536;
    m0 = mod(a0 .* b0, 4294967296);
    m1 = mod(a0 .* b1 + a1 .* b0, 65536);
    r = mod(m0 + m1 .* 65536, 4294967296);
end
function i = noiseUtil_floor(f)
    i = int32(fix(double(f)));
    neg = f < 0;
    i(neg) = i(neg) - int32(1);
end
function G = gradTable()
    G = single([0.13052619 0.9914449; 0.38268343 0.9238795; 0.6087614 0.7933533; 0.6087614 0.7933533;
        0.7933533 0.6087614; 0.9238795 0.38268343; 0.9914449 0.13052619; 0.9914449 0.13052619;
        0.9914449 -0.13052619; 0.9238795 -0.38268343; 0.7933533 -0.6087614; 0.7933533 -0.6087614;
        0.6087614 -0.7933533; 0.38268343 -0.9238795; 0.13052619 -0.9914449; 0.13052619 -0.9914449;
        -0.13052619 -0.9914449; -0.38268343 -0.9238795; -0.6087614 -0.7933533; -0.6087614 -0.7933533;
        -0.7933533 -0.6087614; -0.9238795 -0.38268343; -0.9914449 -0.13052619; -0.9914449 -0.13052619;
        -0.9914449 0.13052619; -0.9238795 0.38268343; -0.7933533 0.6087614; -0.7933533 0.6087614;
        -0.6087614 0.7933533; -0.38268343 0.9238795; -0.13052619 0.9914449; -0.13052619 0.9914449]);
end