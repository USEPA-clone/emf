DROP FUNCTION public.run_max_emis_red_strategy(integer, integer, integer, integer);

CREATE OR REPLACE FUNCTION public.run_max_emis_red_strategy(intControlStrategyId integer, intInputDatasetId integer, 
	intInputDatasetVersion integer, intStrategyResultId int) RETURNS void AS $$
DECLARE
	strategy_name varchar(255) := '';
	inv_table_name varchar(64) := '';
	inv_filter text := '';
	inv_fips_filter text := '';
	detailed_result_dataset_id integer := null;
	detailed_result_table_name varchar(64) := '';
	county_dataset_id integer := null;
	county_dataset_version integer := null;
	region RECORD;
	target_pollutant_id integer := 0;
	target_pollutant character varying(255) := '';
	target_pollutant_ids integer[];
	measures_count integer := 0;
	measure_with_region_count integer := 0;
	measure_classes_count integer := 0;
	county_dataset_filter_sql text := '';
	cost_year integer := null;
	inventory_year integer := null;
	is_point_table boolean := false;
	use_cost_equations boolean := false;
	gimme_count integer := 0;
	min_emis_reduction_constraint real := null;
	min_control_efficiency_constraint real := null;
	max_cost_per_ton_constraint real := null;
	max_ann_cost_constraint real := null;
	replacement_control_min_eff_diff_constraint double precision := null;
	has_constraints boolean := null;
	dataset_month smallint := 0;
	no_days_in_month smallint := 31;
	no_days_in_year smallint := 365;
	discount_rate double precision;
	has_sic_column boolean := false; 
	has_naics_column boolean := false;
	has_target_pollutant integer := 0;
	has_latlong_columns boolean := false;
	has_plant_column boolean := false;
	get_strategt_cost_sql character varying;
	has_rpen_column boolean := false;
	get_strategt_cost_inner_sql character varying;
	annualized_uncontrolled_emis_sql character varying;
	annualized_emis_sql character varying;
	uncontrolled_emis_sql character varying;
	emis_sql character varying;
	percent_reduction_sql character varying;
	remaining_emis_sql character varying;
	inventory_sectors character varying := '';
	include_unspecified_costs boolean := true; 
	has_cpri_column boolean := false;
	has_primary_device_type_code_column boolean := false;
	has_control_ids_column boolean := false;
	creator_user_id integer := 0;
	is_cost_su boolean := false; 
	get_strategty_ceff_equation_sql character varying;
	apply_replacement_controls integer := 1;
	match_major_pollutant boolean := false;

	annual_cost_expression text;
	capital_cost_expression text;
	operation_maintenance_cost_expression text;
	fixed_operation_maintenance_cost_expression text;
	variable_operation_maintenance_cost_expression text;
	annualized_capital_cost_expression text;
	computed_ctl_cost_per_ton_expression text;
	computed_cost_per_ton_expression text;
	actual_equation_type_expression text;

	--support for flat file ds types...
	dataset_type_name character varying(255) := '';
	fips_expression character varying(64) := 'fips';
	plantid_expression character varying(64) := 'plantid';
	pointid_expression character varying(64) := 'pointid';
	stackid_expression character varying(64) := 'stackid';
	segment_expression character varying(64) := 'segment';
	is_flat_file_inventory boolean := false;
	inv_pct_red_expression character varying(256);
	inv_ceff_expression character varying(64) := 'ceff';
	longitude_expression character varying(64) := 'xloc';
	latitude_expression character varying(64) := 'yloc';
	plant_name_expression character varying(64) := 'plant';
	control_ids_expression character varying(255) := 'control_ids';
	
	has_design_capacity_columns boolean := false;
	design_capacity_units_expression character varying(64) := 'design_capacity_unit_numerator,design_capacity_unit_denominator';
	convert_design_capacity_expression text;
BEGIN

	-- get the input dataset info
	select lower(i.table_name)
	from emf.internal_sources i
	where i.dataset_id = intInputDatasetId
	into inv_table_name;

	--get dataset type name
	select dataset_types."name"
	from emf.datasets
	inner join emf.dataset_types
	on datasets.dataset_type = dataset_types.id
	where datasets.id = intInputDatasetId
	into dataset_type_name;

	--if Flat File 2010 Types then change primary key field expression variables...
	IF dataset_type_name = 'Flat File 2010 Point' or dataset_type_name = 'Flat File 2010 Nonpoint' or dataset_type_name = 'Flat File 2010 Merged' THEN
		fips_expression := 'region_cd';
		plantid_expression := 'facility_id';
		pointid_expression := 'unit_id';
		stackid_expression := 'rel_point_id';
		segment_expression := 'process_id';
		inv_ceff_expression := 'ann_pct_red';
		is_flat_file_inventory := true;
		longitude_expression := 'longitude';
		latitude_expression := 'latitude';
		plant_name_expression := 'facility_name';
		control_ids_expression := 'control_ids';
		design_capacity_units_expression := 'design_capacity_units';
		convert_design_capacity_expression := public.get_convert_design_capacity_expression('inv', 'scc', '');
	ELSE
		fips_expression := 'fips';
		plantid_expression := 'plantid';
		pointid_expression := 'pointid';
		stackid_expression := 'stackid';
		segment_expression := 'segment';
		inv_ceff_expression := 'ceff';
		longitude_expression := 'xloc';
		latitude_expression := 'yloc';
		plant_name_expression := 'plant';
		-- if orl types...
		IF dataset_type_name = 'ORL Point Inventory (PTINV)' THEN
			control_ids_expression := 'case when coalesce(cpri,0) <> 0 then coalesce(cpri || '''','''') else '''' end || case when coalesce(csec,0) <> 0 then coalesce(''&'' || csec,'''') end';
		ELSIF dataset_type_name = 'ORL Nonpoint Inventory (ARINV)' THEN
			control_ids_expression := 'coalesce(PRIMARY_DEVICE_TYPE_CODE,'''') || coalesce(''&'' || SECONDARY_DEVICE_TYPE_CODE,'''')';
		ELSIF dataset_type_name = 'ORL Nonroad Inventory (ARINV)' THEN
			control_ids_expression := 'null::character varying';
		ELSIF dataset_type_name = 'ORL Onroad Inventory (MBINV)' THEN
			control_ids_expression := 'null::character varying';
		ELSIF dataset_type_name = 'ORL Merged Inventory' THEN
			control_ids_expression := 'case when coalesce(cpri,0) <> 0 then coalesce(cpri || '''','''') else '''' end || coalesce(PRIMARY_DEVICE_TYPE_CODE,'''')';
		END IF;
		design_capacity_units_expression := 'design_capacity_unit_numerator,design_capacity_unit_denominator';
		convert_design_capacity_expression := public.get_convert_design_capacity_expression('inv', 'scc', '', '');
	END If;

	-- get the detailed result dataset info
	select sr.detailed_result_dataset_id,
		lower(i.table_name)
	from emf.strategy_results sr
		inner join emf.internal_sources i
		on i.dataset_id = sr.detailed_result_dataset_id
	where sr.id = intStrategyResultId
	into detailed_result_dataset_id,
		detailed_result_table_name;

	--get the inventory sector(s)
	select public.concatenate_with_ampersand(distinct name)
	from emf.sectors s
		inner join emf.datasets_sectors ds
		on ds.sector_id = s.id
	where ds.dataset_id = intInputDatasetId
	into inventory_sectors;

	-- see if control strategy has only certain measures specified
	SELECT count(id), 
		count(case when region_dataset_id is not null then 1 else null end)
	FROM emf.control_strategy_measures 
	where control_strategy_measures.control_strategy_id = intControlStrategyId 
	INTO measures_count, 
		measure_with_region_count;

	-- see if measure classes were specified
	IF measures_count = 0 THEN
		SELECT count(1)
		FROM emf.control_strategy_classes 
		where control_strategy_classes.control_strategy_id = intControlStrategyId
		INTO measure_classes_count;
	END IF;

	-- get target pollutant, inv filter, and county dataset info if specified
	SELECT cs.name,
		cs.pollutant_id,
		case when length(trim(cs.filter)) > 0 then '(' || public.alias_inventory_filter(cs.filter, 'inv') || ')' else null end,
		cs.cost_year,
		cs.analysis_year,
		cs.county_dataset_id,
		cs.county_dataset_version,
		cs.use_cost_equations,
		cs.discount_rate / 100,
		coalesce(cs.include_unspecified_costs,true),
		p.name,
		cs.creator_id,
		cs.apply_replacement_controls,
		cs.match_major_pollutant
	FROM emf.control_strategies cs
		inner join emf.pollutants p
		on p.id = cs.pollutant_id
	where cs.id = intControlStrategyId
	INTO strategy_name,
		target_pollutant_id,
		inv_filter,
		cost_year,
		inventory_year,
		county_dataset_id,
		county_dataset_version,
		use_cost_equations,
		discount_rate,
		include_unspecified_costs,
		target_pollutant,
		creator_user_id,
		apply_replacement_controls,
		match_major_pollutant;
		
	-- match target pollutant to list of similar pollutants
	SELECT ARRAY(
		SELECT pollutant_id
		  FROM emf.aggregrated_efficiencyrecords
		  JOIN emf.pollutants
		    ON pollutant_id = pollutants.id
		 WHERE pollutants.name LIKE '%' || 
		  CASE WHEN target_pollutant = 'PM2_5' THEN 'PM2'
		       ELSE target_pollutant
		   END || '%'
		 GROUP BY pollutant_id)
	  INTO target_pollutant_ids;
	  
	-- see if strategyt creator is a CoST SU
	SELECT 
		case 
			when 
				strpos('|' 
				|| (select p.value from emf.properties p where p.name = 'COST_SU') 
				|| '|', '|' || u.username || '|') > 0 then true 
			else false 
		end
	FROM emf.users u
	where u.id = creator_user_id
	INTO is_cost_su;

	-- see if there are point specific columns in the inventory
	is_point_table := public.check_table_for_columns(inv_table_name, '' || plantid_expression || ',' || pointid_expression || ',' || stackid_expression || ',' || segment_expression || '', ',');

	-- see if there is a sic column in the inventory
	has_sic_column := public.check_table_for_columns(inv_table_name, 'sic', ',');

	-- see if there is a naics column in the inventory
	has_naics_column := public.check_table_for_columns(inv_table_name, 'naics', ',');

	-- see if there is a rpen column in the inventory
	has_rpen_column := public.check_table_for_columns(inv_table_name, 'rpen', ',');

	-- see if there is lat & long columns in the inventory
	has_latlong_columns := public.check_table_for_columns(inv_table_name, '' || longitude_expression || ',' || latitude_expression || '', ',');

	-- see if there is plant column in the inventory
	has_plant_column := public.check_table_for_columns(inv_table_name, '' || plant_name_expression || '', ',');

	-- see if there is plant column in the inventory
	has_cpri_column := public.check_table_for_columns(inv_table_name, 'cpri', ',');

	-- see if there is primary_device_type_code column in the inventory
	has_primary_device_type_code_column := public.check_table_for_columns(inv_table_name, 'primary_device_type_code', ',');

	-- see if there is control_ids column in the inventory
	has_control_ids_column := public.check_table_for_columns(inv_table_name, 'control_ids', ',');

	-- see if there are design capacity columns in the inventory
	has_design_capacity_columns := public.check_table_for_columns(inv_table_name, 'design_capacity,' || design_capacity_units_expression, ',');

	-- get strategy constraints
	SELECT csc.max_emis_reduction,
		csc.max_control_efficiency,
		csc.min_cost_per_ton,
		csc.min_ann_cost,
		csc.replacement_control_min_eff_diff
	FROM emf.control_strategy_constraints csc
	where csc.control_strategy_id = intControlStrategyId
	INTO min_emis_reduction_constraint,
		min_control_efficiency_constraint,
		max_cost_per_ton_constraint,
		max_ann_cost_constraint,
		replacement_control_min_eff_diff_constraint;

	select case when min_emis_reduction_constraint is not null 
		or min_control_efficiency_constraint is not null 
		or max_cost_per_ton_constraint is not null 
		or max_ann_cost_constraint is not null then true else false end
	into has_constraints;

	-- get month of the dataset, 0 (Zero) indicates an annual inventory
	select public.get_dataset_month(intInputDatasetId)
	into dataset_month;

	select public.get_days_in_month(dataset_month::smallint, inventory_year::smallint)
	into no_days_in_month;

	select public.get_days_in_year(inventory_year::smallint)
	into no_days_in_year;
	

/*
	EXECUTE '
		CREATE TEMP TABLE inv_overrides (record_id integer NOT NULL, nann_emis double precision, 
		navd_emis double precision,
		nceff double precision, 
		nreff double precision,
		nrpen double precision,
		cm_ids text,
		last_source_apply_order integer,
		already_controlled_pollutant_ids text) ON COMMIT DROP;';

	EXECUTE 'insert into inv_overrides (record_id, nann_emis, navd_emis, nceff, nreff, nrpen, cm_ids, last_source_apply_order, already_controlled_pollutant_ids)
		select distinct on (inv.record_id) 
			inv.record_id,
			case when dr.source_id is not null then dr.final_emissions else ann_emis end as nann_emis, 
			case when dr.source_id is not null then dr.final_emissions / 
			' ||
			case 
				when dataset_month != 0 then 
					no_days_in_month 
				else 
					'365' 
			end || ' else avd_emis end as navd_emis,
			case when dr.source_id is not null then dr.percent_reduction else ceff end as nceff, 
			case when dr.source_id is not null then 100 else reff end as nreff,
			' || case when has_rpen_column then 'case when dr.source_id is not null then 100 else rpen end as nrpen' else 'null::double precision' end || ',
			coalesce((select public.concatenate_with_pipe(CM_Id || '''') from emissions.' || detailed_result_table_name || ' dr where dr.source_id = inv.record_id),''''::text) as cm_ids,
			coalesce(
			(select max(dr_apply_order.apply_order) as last_source_apply_order 
			from emissions.' || detailed_result_table_name || ' dr_apply_order
			where dr_apply_order.fips = inv.fips
			and dr_apply_order.scc = inv.scc
			' || case when is_point_table then '
			and dr_apply_order.plantid = inv.plantid
			and dr_apply_order.pointid = inv.pointid
			and dr_apply_order.stackid = inv.stackid
			and dr_apply_order.pointid = inv.pointid
			and dr_apply_order.segment = inv.segment
			' else '
			and dr_apply_order.plantid is null
			and dr_apply_order.pointid is null
			and dr_apply_order.stackid is null
			and dr_apply_order.pointid is null
			and dr_apply_order.segment is null
			' end || ')
			, 0::integer) as last_source_apply_order,
			coalesce(
			(select public.concatenate_with_pipe(p.id || '''') as already_controlled_pollutant_ids 
			from emissions.' || detailed_result_table_name || ' dr_apply_order
				inner join emf.pollutants p
				on p.name = dr_apply_order.poll
			where dr_apply_order.fips = inv.fips
			and dr_apply_order.scc = inv.scc
			' || case when is_point_table then '
			and dr_apply_order.plantid = inv.plantid
			and dr_apply_order.pointid = inv.pointid
			and dr_apply_order.stackid = inv.stackid
			and dr_apply_order.pointid = inv.pointid
			and dr_apply_order.segment = inv.segment
			' else '
			and dr_apply_order.plantid is null
			and dr_apply_order.pointid is null
			and dr_apply_order.stackid is null
			and dr_apply_order.pointid is null
			and dr_apply_order.segment is null
			' end || ')
			,''''::text) as already_controlled_pollutant_ids


		from emissions.' || inv_table_name || ' inv

			left outer join emissions.' || detailed_result_table_name || ' dr
			on inv.record_id = dr.source_id

		where ' || inv_filter || coalesce(county_dataset_filter_sql, '') || '

		order by inv.record_id, dr.record_id desc;';
*/	



	-- if strategy have measures, then store these in a temp table for later use...
	IF measure_with_region_count > 0 THEN
		EXECUTE '
			CREATE TEMP TABLE measures (control_measure_id integer NOT NULL, region_id integer NOT NULL, region_version integer NOT NULL) ON COMMIT DROP;
			CREATE TEMP TABLE measure_regions (region_id integer NOT NULL, region_version integer NOT NULL, fips character varying(6) NOT NULL) ON COMMIT DROP;

--			CREATE TABLE measures (control_measure_id integer NOT NULL, region_id integer NOT NULL, region_version integer NOT NULL);
--			CREATE TABLE measure_regions (region_id integer NOT NULL, region_version integer NOT NULL, fips character varying(6) NOT NULL);

			CREATE INDEX measure_regions_measure_id ON measures USING btree (control_measure_id);
			CREATE INDEX measure_regions_region ON measures USING btree (region_id, region_version);
			CREATE INDEX regions_region ON measure_regions USING btree (region_id, region_version);
			CREATE INDEX regions_fips ON measure_regions USING btree (fips);';

		FOR region IN EXECUTE 
			'SELECT m.control_measure_id, i.table_name, m.region_dataset_id, m.region_dataset_version
			FROM emf.control_strategy_measures m
				inner join emf.internal_sources i
				on m.region_dataset_id = i.dataset_id
			where m.control_strategy_id = ' || intControlStrategyId || '
				and m.region_dataset_id is not null'
		LOOP
			EXECUTE 'insert into measures (control_measure_id, region_id, region_version)
			SELECT ' || region.control_measure_id || ', ' || region.region_dataset_id || ', ' || region.region_dataset_version || ';';

			EXECUTE 'select count(1)
			from measure_regions
			where region_id = ' || region.region_dataset_id || '
				and region_version = ' || region.region_dataset_version || ''
			into gimme_count;

			IF gimme_count = 0 THEN
				EXECUTE 'insert into measure_regions (region_id, region_version, fips)
				SELECT ' || region.region_dataset_id || ', ' || region.region_dataset_version || ', fips
				FROM emissions.' || region.table_name || '
				where ' || public.build_version_where_filter(region.region_dataset_id, region.region_dataset_version);
			END IF;
		END LOOP;
	END IF;

	-- see if their was a county dataset specified for the strategy, is so then build a sql where clause filter for later use
	IF county_dataset_id is not null THEN
		county_dataset_filter_sql := ' and inv.' || fips_expression || ' in (SELECT fips
			FROM emissions.' || (SELECT table_name FROM emf.internal_sources where dataset_id = county_dataset_id) || '
			where ' || public.build_version_where_filter(county_dataset_id, county_dataset_version) || ')';
	END IF;
	-- build version info into where clause filter
	inv_filter := '(' || public.build_version_where_filter(intInputDatasetId, intInputDatasetVersion, 'inv') || ')' || coalesce(' and ' || inv_filter, '');

/*	EXECUTE '
		SELECT DISTINCT ON (poll) 1::integer as Found 
		FROM emissions.' || inv_table_name || ' as inv
		where ' || inv_filter || county_dataset_filter_sql || '
		and poll = ' || quote_literal((select name from emf.pollutants where id = target_pollutant_id)) || '
		limit 1'
	into has_target_pollutant;
	
	has_target_pollutant := coalesce(has_target_pollutant,0);

	IF has_target_pollutant = 0 THEN
		raise exception 'Target pollutant is not in the inventory';
		return;
	END IF;
*/

--	uncontrolled_emis_sql := case when dataset_month != 0 then 'coalesce(inv.avd_emis * ' || no_days_in_month || ', inv.ann_emis)' else 'inv.ann_emis' end;
--	annualized_uncontrolled_emis_sql := case when dataset_month != 0 then 'coalesce(inv.avd_emis * 365, inv.ann_emis)' else 'inv.ann_emis' end;

			
	IF NOT is_flat_file_inventory THEN
		inv_pct_red_expression := 'coalesce(inv.ceff, inv_ovr.ceff) * coalesce(coalesce(inv.reff, inv_ovr.reff) / 100, 1.0)' || case when has_rpen_column then ' * coalesce(coalesce(inv.rpen, inv_ovr.rpen) / 100, 1.0)' else '' end;
		emis_sql := public.get_ann_emis_expression('inv', no_days_in_month);
		annualized_emis_sql := case when dataset_month != 0 then 'coalesce(inv.avd_emis * ' || no_days_in_year || ', inv.ann_emis)' else 'inv.ann_emis' end;
	ELSE
		inv_pct_red_expression := 'coalesce(inv.ann_pct_red, inv_ovr.ceff)';
		emis_sql := 'inv.ann_value';
		annualized_emis_sql := 'inv.ann_value';
	END IF;

--	uncontrolled_emis_sql := public.get_uncontrolled_ann_emis_expression('inv', no_days_in_month, 'inv_ovr', has_rpen_column);
	uncontrolled_emis_sql := public.get_uncontrolled_emis_expression(inv_pct_red_expression, emis_sql);

--	annualized_uncontrolled_emis_sql := public.get_uncontrolled_ann_emis_expression('inv', no_days_in_month, 'inv_ovr', has_rpen_column);
	annualized_uncontrolled_emis_sql := public.get_uncontrolled_emis_expression(inv_pct_red_expression, annualized_emis_sql);

	-- build sql that calls ceff SQL equation 
/*	get_strategty_ceff_equation_sql := public.get_ceff_equation_expression(
		intInputDatasetId, -- int_input_dataset_id
		inventory_year, -- inventory_year
		'inv', --inv_table_alias character varying(64), 
		'er');*/
	get_strategty_ceff_equation_sql := public.get_ceff_equation_expression(
		annualized_emis_sql, 
		'inv', 
		'er',
		is_point_table);
	

	/*percent_reduction_sql := public.get_control_percent_reduction_expression(intInputDatasetId,
		inventory_year,
		'inv', 
		no_days_in_month, 
		'inv_ovr', 
		measures_count, 
		'csm', 
		'er');*/
	percent_reduction_sql := public.get_control_percent_reduction_expression(
		emis_sql,
		'inv', 
		'er',
		is_point_table, 
		measures_count, 
		'csm');
		
--	percent_reduction_sql := 'er.efficiency * ' || case when measures_count > 0 then 'coalesce(csm.rule_effectiveness, er.rule_effectiveness)' else 'er.rule_effectiveness' end || ' * ' || case when measures_count > 0 then 'coalesce(csm.rule_penetration, er.rule_penetration)' else 'er.rule_penetration' end || ' / 100 / 100';
	-- relative emission reduction from inventory emission (i.e., ann emis in inv 100 tons (add on control gives addtl 50% red --> 50 tons
	-- whereas a 95% ceff replacement control on a source with an existing control of 90% reduction needs to back out source to an 
	-- uncontrolled emission 100 / (1 - 0.9) = 1000 tons giving a and then applying new control gives 950 tons reduced giving a 95% control
	/*remaining_emis_sql := public.get_remaining_emis_expression(intInputDatasetId,
		inventory_year,
		'inv', 
		no_days_in_month, 
		'inv_ovr', 
		measures_count, 
		'csm', 
		'er', 
		has_rpen_column);*/
	remaining_emis_sql := public.get_remaining_emis_expression(
		emis_sql, 
		inv_pct_red_expression,
		'inv', 
		'er',
		is_point_table,
		measures_count,
		'csm');

	
		

	-- get various costing sql expressions (based on discount rate as specified in strategy)
	select annual_cost_expression(cost_expressions),
		capital_cost_expression(cost_expressions),
		operation_maintenance_cost_expression(cost_expressions),
		fixed_operation_maintenance_cost_expression(cost_expressions),
		variable_operation_maintenance_cost_expression(cost_expressions),
		annualized_capital_cost_expression(cost_expressions),
		computed_ctl_cost_per_ton_expression(cost_expressions),
		computed_cost_per_ton_expression(cost_expressions),
		actual_equation_type_expression(cost_expressions)
	from public.get_cost_expressions(
		intControlStrategyId, -- int_control_strategy_id
		intInputDatasetId, -- int_input_dataset_id
		false, --use_override_dataset
		'inv'::varchar, --inv_table_alias character varying(64), 
		'm'::varchar, --control_measure_table_alias character varying(64), 
		'et'::varchar, --equation_type_table_alias character varying(64), 
		'eq'::varchar, --control_measure_equation_table_alias
		'er'::varchar, --control_measure_efficiencyrecord_table_alias
		'csm'::varchar, --control_strategy_measure_table_alias
		'gdplev'::varchar, --gdplev_table_alias
		'inv_ovr'::varchar, --inv_override_table_alias
		'gdplev_incr'::varchar, --gdplev_incr_table_alias
		'scc'::character varying, --control_measure_sccs_table_alias
		discount_rate
		) as cost_expressions
	into annual_cost_expression,
		capital_cost_expression,
		operation_maintenance_cost_expression,
		fixed_operation_maintenance_cost_expression,
		variable_operation_maintenance_cost_expression,
		annualized_capital_cost_expression,
		computed_ctl_cost_per_ton_expression,
		computed_cost_per_ton_expression,
		actual_equation_type_expression;

/*raise notice '%', annual_cost_expression;
raise notice '%', capital_cost_expression;
raise notice '%', operation_maintenance_cost_expression;
raise notice '%', fixed_operation_maintenance_cost_expression;
raise notice '%', variable_operation_maintenance_cost_expression;
raise notice '%', annualized_capital_cost_expression;
raise notice '%', computed_ctl_cost_per_ton_expression;
raise notice '%', computed_cost_per_ton_expression;
raise notice '%', actual_equation_type_expression;

return;
*/	

-- Populate override table (for now just contains overriding values when PM10 or PM2.5 are missing ceff)
	EXECUTE '
		CREATE TEMP TABLE inv_overrides (
			record_id integer NOT NULL, 
			ceff double precision, 
			reff double precision,
			rpen double precision,
			so2_ann_value double precision
		) ON COMMIT DROP;';
		EXECUTE 
--		raise notice '%', 
		'insert into inv_overrides (
				record_id, 
				ceff, 
				reff, 
				rpen, 
				so2_ann_value
			)
		select coalesce(pm_fillin_ceff.record_id, so2_emis.record_id) as record_id, missing_ceff, 100.0, 100.0, so2_emis.so2_ann_value
		from (
			select record_id, missing_ceff, 100.0, 100.0
			from (
				select record_id,
					' || inv_ceff_expression || ',
					first_value(' || inv_ceff_expression || ') over source_window as missing_ceff,
					sum(case when coalesce(inv.' || inv_ceff_expression || ',0.0) = 0.0 then 1 else null end) over source_window as missing_ceff_count,
					sum(1) over source_window as partition_record_count

				from emissions.' || inv_table_name || ' inv

				where ' || inv_filter || coalesce(county_dataset_filter_sql, '') || '
					and inv.poll in (''PM10-PRI'',''PM25-PRI'')

				WINDOW source_window AS (PARTITION BY ' || fips_expression || ',scc' || case when is_point_table = false then '' else ',' || plantid_expression || ',' || pointid_expression || ',' || stackid_expression || ',' || segment_expression || '' end || ' order by ' || fips_expression || ',scc' || case when is_point_table = false then '' else ',' || plantid_expression || ',' || pointid_expression || ',' || stackid_expression || ',' || segment_expression || '' end || ',coalesce(' || inv_ceff_expression || ',0.0) desc)
			) foo
			where missing_ceff_count <> partition_record_count
				and coalesce(' || inv_ceff_expression || ',0.0) = 0.0
			) pm_fillin_ceff

			--get so2 emission for equation type 16
			full join (
				select record_id,so2_ann_value
				from (
					select record_id,poll,
						sum(case when poll = ''SO2'' then ' || emis_sql || ' else null::double precision end) over source_window as so2_ann_value

					from emissions.' || inv_table_name || ' inv

					where ' || inv_filter || coalesce(county_dataset_filter_sql, '') || '
						and inv.poll in (''PM10-PRI'',''PM25-PRI'',''SO2'')

					WINDOW source_window AS (PARTITION BY ' || fips_expression || ',scc' || case when is_point_table = false then '' else ',' || plantid_expression || ',' || pointid_expression || ',' || stackid_expression || ',' || segment_expression || '' end || ')

				) tbl
				where so2_ann_value is not null and poll in (''PM10-PRI'',''PM25-PRI'')
			) so2_emis

			on pm_fillin_ceff.record_id = so2_emis.record_id';

/*		FOR region IN EXECUTE 
			'SELECT record_id, nann_emis, navd_emis, nceff, nreff, nrpen, cm_ids, last_source_apply_order, already_controlled_pollutant_ids
			FROM inv_overrides where length(already_controlled_pollutant_ids)>0'
		LOOP
			raise notice '%', '' || region.record_id || ', ' || region.last_source_apply_order || ', ' || region.already_controlled_pollutant_ids || ';';

		END LOOP;
*/


		EXECUTE 'CREATE INDEX inv_overrides_record_id ON inv_overrides USING btree (record_id);';



	-- add both target and cobenefit pollutants, first get best target pollutant measure, then use that to apply to other pollutants.
	execute
--	raise notice '%', 
	'insert into emissions.' || detailed_result_table_name || ' 
		(
		dataset_id,
		cm_abbrev,
		poll,
		scc,
		region_cd,
		facility_id, 
		unit_id, 
		rel_point_id, 
		process_id,
		annual_oper_maint_cost,
		annual_variable_oper_maint_cost,
		annual_fixed_oper_maint_cost,
		annualized_capital_cost,
		total_capital_cost,
		annual_cost,
		ctl_ann_cost_per_ton,
		eff_ann_cost_per_ton,
		control_eff,
		rule_pen,
		rule_eff,
		percent_reduction,
		control_ids,
		inv_ctrl_eff,
		inv_rule_pen,
		inv_rule_eff,
		final_emissions,
		ctl_emis_reduction,
		eff_emis_reduction,
		inv_emissions,
		input_emis,
		output_emis,
		fipsst,
		fipscty,
		sic,
		naics,
		source_id,
		input_ds_id,
		cs_id,
		cm_id,
		equation_type,
		sector,
		xloc,
		yloc,
		facility,
		"comments",
		REPLACEMENT_ADDON,
		EXISTING_MEASURE_ABBREVIATION,
		EXISTING_PRIMARY_DEVICE_TYPE_CODE,
		strategy_name,
		target_poll,
		control_technology,
		source_group,
		apply_order,
		county_name,
		state_name,
		scc_l1,
		scc_l2,
		scc_l3,
		scc_l4,
		design_capacity,
		design_capacity_units,
		stkflow,
		stkvel,
		stkdiam,
		stktemp,
		annual_avg_hours_per_year,
		so2_emissions
		)

--	create table emissions.mer_new as 
select 
	' || detailed_result_dataset_id || '::integer as dataset_id,
	abbreviation,
	poll,
	scc,
	fips,
	plantid,
	pointid,
	stackid,
	segment,
	operation_maintenance_cost,
	annual_variable_oper_maint_cost,
	annual_fixed_oper_maint_cost,
	annualized_capital_cost,
	capital_cost,
	ann_cost,
	computed_ctl_cost_per_ton,
	computed_cost_per_ton,
	efficiency,
	rule_pen,
	rule_eff,
	percent_reduction,
	control_ids,
	ceff,
	rpen,
	reff,
	final_emissions,
	ctl_emis_reduction,
	emis_reduction,
	inv_emissions,
	input_emis,
	output_emis,
	fipsst,
	fipscty,
	sic,
	naics,
	source_id,
	' || intInputDatasetId || '::integer as input_ds_id,
	' || intControlStrategyId || '::integer as cs_id,
	control_measures_id,equation_type,sector,xloc,yloc,plant,'''' as comment,
	replacement_addon,
	existing_measure_abbr,
	existing_dev_code,
	strategy_name,
	target_poll,
	control_technology,
	source_group, 
	1 as apply_order,
	county,
	state_name,
	scc_l1,
	scc_l2,
	scc_l3,
	scc_l4,
	design_capacity,
	design_capacity_units,
	stkflow,
	stkvel,
	stkdiam,
	stktemp,
	annual_avg_hours_per_year,
	so2_emissions
--	,source_tp_remaining_emis,source_tp_count,source_annual_cost, winner

from (
select 
	*	, rank() OVER (PARTITION BY fips,scc,plantid,pointid,stackid,segment
			order by fips,scc,plantid,pointid,stackid,segment,source_tp_remaining_emis,coalesce(source_annual_cost,0.0),source_poll_count desc,abbreviation) as winner
from (
-- did sum over window here, becuase REQUIRED inner distinct clause was causing the windowing functions to not aggregrate correclty!!!!
select 
	*	, sum(ann_cost) OVER w as source_annual_cost,
			sum( case when pollutant_id = ANY (''' || target_pollutant_ids::varchar || ''') then 1 else 0 end ) OVER w as source_tp_count,
			sum( 1 ) OVER w as source_poll_count,
			sum(case when pollutant_id = ANY (''' || target_pollutant_ids::varchar || ''') then final_emissions else null::double precision end) OVER w  as source_tp_remaining_emis			
	from (


		-- get best measures for sources target pollutants (and related cobenefits), there could be a tie for a paticular source
		-- get all matches, dont worry if source doesnt have the target pollutant of interest (subsuquent pass of data will filter on sources with target pollutant if affected)
		
		select DISTINCT ON (inv.record_id,er.control_measures_id) 
--		select DISTINCT ON (inv.' || fips_expression || ', inv.scc' || case when is_point_table = false then '' else ', inv.' || plantid_expression || ', inv.' || pointid_expression || ', inv.' || stackid_expression || ', inv.' || segment_expression || '' end || ', er.control_measures_id,inv.record_id) 
			p.id as pollutant_id,
			m.abbreviation,
			inv.poll,
			inv.scc,
			inv.' || fips_expression || ' as fips,
			' || case when is_point_table = false then 'null::character varying(15) as plantid, null::character varying(15) as pointid, null::character varying(15) as stackid, null::character varying(15) as segment, ' else 'inv.' || plantid_expression || ' as plantid, inv.' || pointid_expression || ' as pointid, inv.' || stackid_expression || ' as stackid, inv.' || segment_expression || ' as segment, ' end || '
			' || operation_maintenance_cost_expression || '  as operation_maintenance_cost,
			' || variable_operation_maintenance_cost_expression || '  as annual_variable_oper_maint_cost,
			' || fixed_operation_maintenance_cost_expression || '  as annual_fixed_oper_maint_cost,
			' || annualized_capital_cost_expression || '  as annualized_capital_cost,
			' || capital_cost_expression || ' as capital_cost,
			' || annual_cost_expression || ' as ann_cost,
			' || computed_ctl_cost_per_ton_expression || '  as computed_ctl_cost_per_ton,
			' || computed_cost_per_ton_expression || '  as computed_cost_per_ton,

			' || get_strategty_ceff_equation_sql || ' as efficiency,
			' || case when measures_count > 0 then 'coalesce(csm.rule_penetration, er.rule_penetration)' else 'er.rule_penetration' end || ' as rule_pen,
			' || case when measures_count > 0 then 'coalesce(csm.rule_effectiveness, er.rule_effectiveness)' else 'er.rule_effectiveness' end || ' as rule_eff,
			' || percent_reduction_sql || ' as percent_reduction,
			' || control_ids_expression || ' as control_ids,
			coalesce(inv_ovr.ceff, inv.' || inv_ceff_expression || ') as ceff,
			' || case when not is_point_table and not is_flat_file_inventory then 'coalesce(inv_ovr.rpen, inv.rpen)' else '100' end || ' as rpen,
			' || case when not is_flat_file_inventory then 'coalesce(inv_ovr.reff, inv.reff)' else '100' end || ' as reff,
			' || remaining_emis_sql || ' as final_emissions,
			case when coalesce(er.existing_measure_abbr, '''') <> '''' or er.existing_dev_code <> 0 then ' || emis_sql || ' else ' || uncontrolled_emis_sql || ' end - ' || remaining_emis_sql || ' as ctl_emis_reduction,
			' || emis_sql || ' - ' || remaining_emis_sql || ' as emis_reduction,
			' || emis_sql || ' as inv_emissions,
			case when coalesce(er.existing_measure_abbr, '''') <> '''' or er.existing_dev_code <> 0 then ' || emis_sql || ' else ' || uncontrolled_emis_sql || ' end as input_emis,
			' || remaining_emis_sql || ' as output_emis,
			substr(inv.' || fips_expression || ', 1, 2) as fipsst,
			substr(inv.' || fips_expression || ', 3, 3) as fipscty,
			' || case when has_sic_column = false then 'null::character varying' else 'inv.sic' end || ' as sic,
			' || case when has_naics_column = false then 'null::character varying' else 'inv.naics' end || ' as naics,
			inv.record_id::integer as source_id,
			er.control_measures_id,
			' || coalesce(actual_equation_type_expression, quote_literal('')) || ' as equation_type,
			' || quote_nullable(inventory_sectors) || ' as sector,
			' || case when has_latlong_columns then 'inv.' || longitude_expression || ' as xloc,inv.' || latitude_expression || ' as yloc,' else 'fipscode.centerlon as xloc,fipscode.centerlat as yloc,' end || '
			' || case when has_plant_column then 'inv.' || plant_name_expression || ' as plant' when not has_latlong_columns then 'fipscode.state_county_fips_code_desc as plant' else 'null::character varying as plant' end || ',
			case when coalesce(er.existing_measure_abbr, '''') <> '''' or er.existing_dev_code <> 0 then ''A''
						else ''R'' end as REPLACEMENT_ADDON,
			er.existing_measure_abbr,
			er.existing_dev_code,
			' || quote_literal(strategy_name) || ' as strategy_name,
			' || quote_literal(target_pollutant) || ' as target_poll,
			ct.name as control_technology,
			sg.name as source_group,
			fipscode.county,
			fipscode.state_name,
			scc_codes.scc_l1,
			scc_codes.scc_l2,
			scc_codes.scc_l3,
			scc_codes.scc_l4,
			' || case when has_design_capacity_columns = false then 'null::double precision as design_capacity, null::character varying(20) as design_capacity_units, ' else 'inv.design_capacity,
			' || case when is_flat_file_inventory then 'inv.design_capacity_units, 'else 'inv.design_capacity_unit_numerator as design_capacity_units, ' end || '
			' end || '
			' || case when is_point_table = false then 'null::double precision as stkflow, null::double precision as stkvel, null::double precision as stkdiam, null::double precision as stktemp, null::double precision as annual_avg_hours_per_year, ' else 'inv.stkflow, inv.stkvel, inv.stkdiam, inv.stktemp, inv.annual_avg_hours_per_year, ' end || '
			inv_ovr.so2_ann_value as so2_emissions

		FROM emissions.' || inv_table_name || ' inv

			inner join emf.pollutants p
			on p.name = inv.poll

			left outer join inv_overrides inv_ovr
			on inv_ovr.record_id = inv.record_id

			inner join emf.control_measure_sccs scc
			on scc.name = inv.scc
			
			' || case when measures_count > 0 then '
			inner join emf.control_strategy_measures csm
			on csm.control_measure_id = scc.control_measures_id
			and csm.control_strategy_id = ' || intControlStrategyId || '
			' else '' end || '

			--this part will get applicable measure based on the target pollutant, 
			-- use this measure for target and cobenefit pollutants...
			inner join emf.control_measures m
			on m.id = scc.control_measures_id

-- for Non CoST SUs, make sure they only see their temporary measures
			' || case when not is_cost_su then '

			inner join emf.control_measure_classes cmc
			on cmc.id = m.cm_class_id
			and (
				(cmc.name = ''Temporary'' and m.creator = ' || creator_user_id || ')
				or (cmc.name <> ''Temporary'')
			)
			' else '' end || '

			inner join emf.control_measure_months ms
			on ms.control_measure_id = m.id
			and ms.month in (0' || case when dataset_month != 0 then ',' || dataset_month else '' end || ')

			left outer join emf.control_measure_equations eq
			on eq.control_measure_id = m.id
			and eq.pollutant_id = p.id

			left outer join emf.equation_types et
			on et.id = eq.equation_type_id

			left outer join reference.gdplev
			on gdplev.annual = eq.cost_year

			inner join emf.control_measure_efficiencyrecords er
			on er.control_measures_id = scc.control_measures_id
			-- pollutant filter
			and er.pollutant_id = p.id
			-- min and max emission filter
			and ' || annualized_uncontrolled_emis_sql || ' between coalesce(er.min_emis, -1E+308) and coalesce(er.max_emis, 1E+308)
--					and source_total.total_ann_emis between coalesce(er.min_emis, -1E+308) and coalesce(er.max_emis, 1E+308)
			-- locale filter
			and (er.locale = inv.' || fips_expression || ' or er.locale = substr(inv.' || fips_expression || ', 1, 2) or er.locale = '''')
			-- effecive date filter
			and ' || inventory_year || '::integer >= coalesce(date_part(''year'', er.effective_date), ' || inventory_year || '::integer)		
			-- Replacement vs Add On Logic...
			and (

				-- Measure is Add On Only!
				(
					(coalesce(er.existing_measure_abbr, '''') <> '''' or er.existing_dev_code <> 0)
					and 
					(
						(length(inv.control_measures) > 0 and strpos(''&'' || coalesce(inv.control_measures, '''') || ''&'', ''&'' || er.existing_measure_abbr || ''&'') > 0) '
						|| case when has_cpri_column then ' or (inv.cpri <> 0 and er.existing_dev_code = inv.cpri) '
						when has_primary_device_type_code_column then ' or (length(inv.primary_device_type_code) > 0 and er.existing_dev_code || '''' = inv.primary_device_type_code) '
						when has_control_ids_column then ' or (length(inv.control_ids) > 0 and strpos(''&'' || coalesce(inv.control_ids, '''') || ''&'', ''&'' || er.existing_dev_code || ''&'') > 0) '
						else '' end || '
					)
				)

				-- Measure is Replacement Only!
				or 
				(
					coalesce(er.existing_measure_abbr, '''') = '''' and coalesce(er.existing_dev_code, 0) = 0
				)


			)
			-- capacity restrictions
			and ((er.min_capacity IS NULL and er.max_capacity IS NULL) '
			     || case when has_design_capacity_columns then '
			     or
			     (COALESCE(' || convert_design_capacity_expression || ', 0) <> 0 and
			      COALESCE(' || convert_design_capacity_expression || ', 0) BETWEEN
			        COALESCE(er.min_capacity, -1E+308) and
			        COALESCE(er.max_capacity, 1E+308)) '
			     else '' end || ')


			left outer join reference.gdplev gdplev_incr
			on gdplev_incr.annual = er.cost_year

			' || case when measures_count = 0 and measure_classes_count > 0 then '
			inner join emf.control_strategy_classes csc
			on csc.control_measure_class_id = m.cm_class_id
			and csc.control_strategy_id = ' || intControlStrategyId || '
			' else '' end || '

			-- target pollutant filter
--					inner join emf.control_strategy_target_pollutants cstp
--					on cstp.pollutant_id = p.id
--					and cstp.control_strategy_id = ' || intControlStrategyId || '

			left outer join emf.control_measure_properties ceff_et
			on ceff_et.control_measure_id = m.id
			and ceff_et."name" = ''CEFF_EQUATION_'' || inv.poll || ''_TYPE''

			left outer join emf.control_measure_properties ceff_var1
			on ceff_var1.control_measure_id = m.id
			and ceff_var1."name" = ''CEFF_EQUATION_'' || inv.poll || ''_VAR1''

			left outer join emf.control_measure_properties ceff_var2
			on ceff_var2.control_measure_id = m.id
			and ceff_var2."name" = ''CEFF_EQUATION_'' || inv.poll || ''_VAR2''

			left outer join reference.fips fipscode
			on fipscode.state_county_fips = inv.' || fips_expression || '
			and fipscode.country_num = ''0''

			left outer join reference.scc_codes
			on scc_codes.scc = inv.scc

			left outer join emf.control_technologies ct
			on ct.id = m.control_technology

			left outer join emf.source_groups sg
			on sg.id = m.source_group

		where 	' || inv_filter || coalesce(county_dataset_filter_sql, '') || '
			--and p.id = ' ||  target_pollutant_id || '

			-- dont include sources that have no emissions...
			and (' || uncontrolled_emis_sql || ') <> 0.0

			-- dont include measures with no specified ceff (look for null values)...
			and (' || get_strategty_ceff_equation_sql || ') is not null

			-- only relevant for target pollutant
			and (
				(p.id = ANY (''' || target_pollutant_ids::varchar || ''')

				-- check major pollutant against target
				' || case when match_major_pollutant then '
				and m.major_pollutant = ANY (''' || target_pollutant_ids::varchar || ''')
				' else '' end || '

				-- dont include sources that have been fully controlled...
				and coalesce(' || inv_pct_red_expression || ', 0) < 100.0


				-- make sure the new control is worthy
				-- this is only relevant for Replacement controls, not Add-on controls or sources with no existing control
				and (
					-- source has no existing control
					(
						coalesce(inv_ovr.ceff, inv.' || inv_ceff_expression || ', 0.0) = 0.0
					)
					-- control is add-on type
					or (
						(coalesce(er.existing_measure_abbr, '''') <> '''' or er.existing_dev_code <> 0)
					)
					-- replacement control that meets the constraint, source has existing control
					or (
						coalesce(inv_ovr.ceff, inv.' || inv_ceff_expression || ', 0.0) <> 0.0
						and (coalesce(er.existing_measure_abbr, '''') = '''' and coalesce(er.existing_dev_code, 0) = 0)
						and ((' ||  emis_sql || ') - (' || remaining_emis_sql || ')) / (' ||  emis_sql || ') * 100 >= ' || replacement_control_min_eff_diff_constraint || '
					)
				) 

				-- dont include measures already on the source...
				and strpos(''&'' || coalesce(inv.control_measures, '''') || ''&'', ''&'' || m.abbreviation || ''&'') = 0

				-- constraints filter
				' || case when has_constraints then '
				and (
						(' || percent_reduction_sql || ') >= ' || coalesce(min_control_efficiency_constraint, -100.0) || '
						' || coalesce(' and (' ||  emis_sql || ') - (' || remaining_emis_sql || ') >= ' || min_emis_reduction_constraint, '')  || '
						' || coalesce(' and coalesce(' || computed_cost_per_ton_expression || ', -1E+308) <= ' || max_cost_per_ton_constraint, '')  || '
						' || coalesce(' and coalesce(' || annual_cost_expression || ', -1E+308) <= ' || max_ann_cost_constraint, '')  || '
				)' else '' end || ')
				or
				p.id != ALL (''' || target_pollutant_ids::varchar || ''')

			)
			
			-- measure region filter
			' || case when measure_with_region_count > 0 then '
			and 
			(
				csm.region_dataset_id is null 
				or
				(
					csm.region_dataset_id is not null 
					and exists (
						select 1
						from measures mr
							inner join measure_regions r
							on r.region_id = mr.region_id
							and r.region_version = mr.region_version
						where mr.control_measure_id = m.id
							and r.fips = inv.' || fips_expression || '
					)
					and exists (
						select 1 
						from measures mr
						where mr.control_measure_id = m.id
					)
				)
			)					
			' else '' end || '
			
			-- restrict sources based on replacement control setting

			-- 0 = only include sources where control ids are blank or ceff is set
			' || case when apply_replacement_controls = 0 then '
			and (coalesce(control_ids, '''') = '''' or
			     coalesce(inv.' || inv_ceff_expression || ', 0.0) <> 0.0)

      -- 2 = for sources with control ids but no ceff, match control ids to reference
      --     and include sources where devices don''t control the source''s pollutant
      ' when apply_replacement_controls = 2 then '
      and (coalesce(control_ids, '''') = '''' or
           coalesce(inv.' || inv_ceff_expression || ', 0.0) <> 0.0 or
           (select count(*) 
              from reference.control_device 
             where control_device_code::varchar = any(string_to_array(control_ids, ''&''))
               and (pollutants = ''Any''
                or  poll = any(string_to_array(pollutants, '',''))))::integer = 0)
			' else '' end || '

		order by inv.record_id,
			er.control_measures_id, 
--		order by inv.' || fips_expression || ', 
--			inv.scc, ' || case when not is_point_table then '' else 'inv.' || plantid_expression || ', inv.' || pointid_expression || ', inv.' || stackid_expression || ', inv.' || segment_expression || ', ' end || '
--			er.control_measures_id, inv.record_id,
			case when length(er.locale) = 5 then 0 when length(er.locale) = 2 then 1 else 2 end, 
			' || remaining_emis_sql || ',
			(' || annual_cost_expression || ') 
	) foo

	WINDOW w AS (PARTITION BY fips,scc,plantid,pointid,stackid,segment,control_measures_id)
	) foo
where source_tp_count > 0	-- this limits to only measures that actually controlled the target pollutant
 ' || case 
	when include_unspecified_costs then '' 
	else 'and source_annual_cost is not null	-- this limits to only measures which actual costs'
end || '
	) foo
	where winner = 1';


return;

END;
$$ LANGUAGE plpgsql;
