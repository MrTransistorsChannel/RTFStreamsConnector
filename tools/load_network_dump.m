function networks = load_network_dump(folder)
%LOAD_NETWORK_DUMP Load the newest rtfconnector JSON river dump into the sim's
%`networks` struct array (roots). Returns [] when `folder` has no *.json files.
%The dump is written by the game command /rtfconnector dumprivers (the file
%name is irrelevant: the NEWEST *.json by modification time wins).
%Keep in sync with makeRiver/makeWarp in rtf_river_sim.m

networks = [];

d = dir(fullfile(folder, '*.json'));
if isempty(d)
    return;
end
[~, idx] = max([d.datenum]);
j = jsondecode(fileread(fullfile(folder, d(idx).name)));

if ~isfield(j, 'rivers')
    return;
end
r = j.rivers;
% jsondecode quirks: a river list is normally a struct array; guard against an
% empty list (double []) and against a single top-level river object.
if isempty(r) || ~isstruct(r)
    return;
end

networks = buildNode(r(1));
for i = 2:numel(r)
    networks(i) = buildNode(r(i));
end

end

function node = buildNode(j)
% Build one sim network node from a JSON river object (recurses into children).
% Keep in sync with makeRiver/makeWarp in rtf_river_sim.m - the math is
% reproduced here because this loader cannot call the script's local functions.

% ---- makeRiver(x1, z1, x2, z2) ----
rv.x1 = j.x1; rv.z1 = j.z1; rv.x2 = j.x2; rv.z2 = j.z2;
rv.dx = rv.x2 - rv.x1;      rv.dz = rv.z2 - rv.z1;
rv.length  = sqrt(rv.dx^2 + rv.dz^2);
rv.length2 = rv.length^2;
rv.ndx = rv.dx/rv.length;    rv.ndz = rv.dz/rv.length;
rv.normX = rv.ndz;           rv.normZ = -rv.ndx;
rv.minX = min(rv.x1, rv.x2);  rv.maxX = max(rv.x1, rv.x2);
rv.minZ = min(rv.z1, rv.z2);  rv.maxZ = max(rv.z1, rv.z2);

% ---- makeWarp(seed, scale, frequency, lower, upper) ----
wp.seed = int32(j.seed);
wp.scale = j.scale;
wp.frequency = j.frequency;
wp.lower = j.lower;
wp.upper = j.upper;
wp.lowerRange = 1.0/j.lower;
wp.upperRange = 1.0/(1.0 - j.upper);

node.river = rv;
node.warp = wp;
node.fade = j.fade;
node.bedWidth = j.bedMax;
node.banksWidth = j.banksMax;
node.valleyWidth = j.valleyMax;
node.valleyCurveL = j.curveL;
node.valleyCurveU = j.curveU;

if isfield(j, 'children') && isstruct(j.children) && ~isempty(j.children)
    children = buildNode(j.children(1));
    for k = 2:numel(j.children)
        children(k) = buildNode(j.children(k));
    end
    node.children = children;
else
    node.children = [];
end

end